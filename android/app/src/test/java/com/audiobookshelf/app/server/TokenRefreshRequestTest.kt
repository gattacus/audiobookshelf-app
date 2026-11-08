package com.audiobookshelf.app.server

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real OkHttp reads, including the interrupted response body observed on the phone. */
class TokenRefreshRequestTest {
  private data class Reply(val status: Int = 200, val body: String = TOKENS, val declaredLength: Int? = null)
  private val servers = mutableListOf<TestServer>()
  private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

  private class TestServer(private val replies: List<Reply>) : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val address = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<List<String>>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val worker = thread(isDaemon = true, name = "token-refresh-test") {
      try {
        replies.forEach { reply ->
          socket.accept().use { connection ->
            connection.soTimeout = 3000
            val reader = connection.getInputStream().bufferedReader()
            val headers = mutableListOf<String>()
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              headers.add(line)
            }
            requests.add(headers)
            val body = reply.body.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 ${reply.status} Test\r\nContent-Type: application/json\r\n" +
              "Content-Length: ${reply.declaredLength ?: body.size}\r\nConnection: close\r\n\r\n"
            connection.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            connection.getOutputStream().write(body)
            connection.getOutputStream().flush()
          }
        }
      } catch (e: SocketException) {
        if (!socket.isClosed) failures.add(e)
      } catch (e: Throwable) { failures.add(e) }
    }

    override fun close() {
      socket.close()
      worker.join(4000)
      assertFalse("Test server did not stop", worker.isAlive)
      assertTrue("Test server failed: $failures", failures.isEmpty())
    }
  }

  private fun server(vararg replies: Reply) = TestServer(replies.toList()).also { servers.add(it) }

  private fun request(address: String): TokenRefreshRequest.Result {
    val done = CountDownLatch(1)
    val completions = AtomicInteger()
    var result: TokenRefreshRequest.Result? = null
    TokenRefreshRequest(client).execute(address, "synthetic-refresh") {
      completions.incrementAndGet()
      result = it
      done.countDown()
    }
    assertTrue("Refresh callback did not complete", done.await(5, TimeUnit.SECONDS))
    assertEquals(1, completions.get())
    return result!!
  }

  @After fun tearDown() {
    servers.forEach { it.close() }
    client.dispatcher.executorService.shutdownNow()
    client.connectionPool.evictAll()
  }

  @Test fun truncatedSuccessfulResponseCanBeRetriedWithOriginalCredential() {
    val server = server(Reply(body = "{\"user\":", declaredLength = 1000), Reply())
    assertTrue(request(server.address) is TokenRefreshRequest.Result.Transient)
    assertEquals(TokenRefreshRequest.Result.Tokens("new-access", "new-refresh"), request(server.address))
    assertEquals(2, server.requests.size)
    server.requests.forEach { headers ->
      assertEquals("POST /auth/refresh HTTP/1.1", headers.first())
      assertTrue(headers.any { it.equals("x-refresh-token: synthetic-refresh", ignoreCase = true) })
    }
  }

  @Test fun temporaryHttpErrorsRemainRetryable() {
    listOf(400, 404, 408, 429, 500, 502, 503).forEach { status ->
      assertTrue("HTTP $status", request(server(Reply(status, "unavailable")).address) is TokenRefreshRequest.Result.Transient)
    }
  }

  @Test fun onlyExplicitRefreshRejectionRequiresLogin() {
    listOf(401, 403).forEach { status ->
      assertEquals(TokenRefreshRequest.Result.Rejected(status), request(server(Reply(status, "rejected")).address))
    }
  }

  @Test fun malformedOrIncompleteSuccessRemainsRetryable() {
    listOf("not json", "", "null", "{}", "{\"user\":{}}", "{\"user\":{\"accessToken\":42}}",
      "{\"user\":{\"accessToken\":\" \"}}").forEach { body ->
      assertTrue(request(server(Reply(body = body)).address) is TokenRefreshRequest.Result.Transient)
    }
  }

  @Test fun omittedRefreshRotationRetainsOriginalCredential() {
    val server = server(Reply(body = "{\"user\":{\"accessToken\":\"new-access\"}}"))
    assertEquals(TokenRefreshRequest.Result.Tokens("new-access", "synthetic-refresh"), request(server.address))
  }

  @Test fun transportFailureIsRetryable() {
    val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    socket.close()
    assertTrue(request("http://127.0.0.1:$port") is TokenRefreshRequest.Result.Transient)
  }

  @Test fun invalidAddressStillCompletesCallback() {
    assertTrue(request("invalid address") is TokenRefreshRequest.Result.Transient)
  }

  companion object {
    private const val TOKENS = "{\"user\":{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\"}}"
  }
}
