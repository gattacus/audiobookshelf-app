package com.audiobookshelf.app.media

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.server.ApiHandler
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP callbacks and Paper records, confined to synthetic accounts and cache storage. */
@RunWith(AndroidJUnit4::class)
class OfflineSyncNetworkDeviceTest {
  private lateinit var context: Context
  private val db = DbManager()
  private val books = listOf("playbackSession", "localMediaProgress", "device", "log", "mediaItemHistory")
  private val servers = mutableListOf<TestServer>()
  private var previousConfig: ServerConnectionConfig? = null

  @Before fun setUp() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val isolatedFiles = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = isolatedFiles
      override fun getSharedPreferences(name: String, mode: Int) =
        target.getSharedPreferences("offline-sync-test-$name", mode)
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach {
      val book = Paper.book(it)
      check(File(book.path).parentFile == context.filesDir) { "Refusing to touch app user data" }
      book.destroy()
    }
    previousConfig = DeviceManager.serverConnectionConfig
    DeviceManager.serverConnectionConfig = null
  }

  @After fun tearDown() {
    servers.forEach { it.close() }
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    DeviceManager.serverConnectionConfig = previousConfig
    books.forEach { Paper.book(it).destroy() }
  }

  @Test fun changingAccountWhileUserRequestIsPendingSkipsOldProgressAndReleasesCoordinator() {
    val userRequested = CountDownLatch(1)
    val releaseUser = CountDownLatch(1)
    val server = server { request ->
      if (request.path == "/api/me") {
        userRequested.countDown()
        check(releaseUser.await(5, TimeUnit.SECONDS))
      }
      Response(USER)
    }
    val accountA = config("a", server.address)
    val accountB = config("b", server.address) // Same host and item IDs, different user.
    DeviceManager.serverConnectionConfig = accountA
    db.saveLocalMediaProgress(progress("local-book", "book"))
    val done = CountDownLatch(1)
    val completions = AtomicInteger()
    val api = ApiHandler(context)
    api.syncLocalSessionsAndProgress { _, _ -> completions.incrementAndGet(); done.countDown() }
    assertTrue(userRequested.await(5, TimeUnit.SECONDS))
    DeviceManager.serverConnectionConfig = accountB
    releaseUser.countDown()
    assertTrue("Old sync must finish instead of holding the coordinator", done.await(5, TimeUnit.SECONDS))
    assertEquals(1, completions.get())
    assertEquals(listOf("/api/me"), server.requests.map { it.path })
    assertEquals("Bearer token-a", server.requests.single().authorization)
    assertEquals(40.0, db.getLocalMediaProgress("local-book")!!.currentTime, 0.001)

    // A new call for the original config must run normally, not coalesce into a leaked old run.
    DeviceManager.serverConnectionConfig = accountA
    val retry = CountDownLatch(1)
    api.syncLocalSessionsAndProgress { _, _ -> retry.countDown() }
    assertTrue(retry.await(5, TimeUnit.SECONDS))
    assertEquals(1, server.requests.count { it.path == "/api/me/progress/book" })
  }

  @Test fun explicitProgressPatchUsesCapturedAddressAndTokenAfterAccountSwitch() {
    val first = server { Response("OK") }
    val second = server { Response("OK") }
    val accountA = config("a", first.address).copy()
    DeviceManager.serverConnectionConfig = config("b", second.address)
    val done = CountDownLatch(1)
    var success = false
    ApiHandler(context).updateMediaProgress("book", null, JSObject().put("currentTime", 40), accountA) {
      success = it
      done.countDown()
    }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertTrue(success)
    assertEquals("/api/me/progress/book", first.requests.single().path)
    assertEquals("Bearer token-a", first.requests.single().authorization)
    assertTrue(second.requests.isEmpty())
  }

  @Test fun simultaneousSuccessfulAndFailedPatchesCompleteOnceAndAllowAnotherSync() {
    val userRequested = CountDownLatch(1)
    val releaseUser = CountDownLatch(1)
    // OkHttp permits five concurrent calls per host. Release each group at the same instant.
    val responsesTogether = CyclicBarrier(5)
    val patches = AtomicInteger()
    val server = server { request ->
      if (request.path == "/api/me") {
        userRequested.countDown()
        check(releaseUser.await(5, TimeUnit.SECONDS))
        Response(USER)
      } else {
        val number = patches.incrementAndGet()
        responsesTogether.await(5, TimeUnit.SECONDS)
        if (number % 2 == 0) Response("OK") else Response("failure", 500)
      }
    }
    DeviceManager.serverConnectionConfig = config("a", server.address)
    repeat(20) { db.saveLocalMediaProgress(progress("local-$it", "book-$it")) }
    val api = ApiHandler(context)
    val completions = AtomicInteger()
    val done = CountDownLatch(2)
    val complete: (Boolean, String?) -> Unit = { _, _ -> completions.incrementAndGet(); done.countDown() }
    api.syncLocalSessionsAndProgress(complete)
    assertTrue(userRequested.await(5, TimeUnit.SECONDS))
    api.syncLocalSessionsAndProgress(complete) // Coalesced caller must also finish.
    releaseUser.countDown()
    assertTrue("Simultaneous patch callbacks must drain the coordinator", done.await(10, TimeUnit.SECONDS))
    assertEquals(2, completions.get())
    assertEquals(20, patches.get())
    assertEquals(1, server.requests.count { it.path == "/api/me" })
    assertEquals(20, db.getAllLocalMediaProgress().size) // Failures never delete offline progress.

    val next = CountDownLatch(1)
    api.syncLocalSessionsAndProgress { _, _ -> next.countDown() }
    assertTrue("A later sync must start and finish", next.await(10, TimeUnit.SECONDS))
    assertEquals(40, patches.get())
  }

  @Test fun previousPlaybackAcknowledgementCannotConsumeNewSessionListeningTimeOnDevice() {
    val tracker = ListeningTimeTracker()
    tracker.start(1_000L, false)
    val old = tracker.onTick(16_000L)!!
    tracker.start(20_000L, false)
    tracker.onTick(35_000L)
    tracker.onServerSyncComplete(36_000L, true, old.serverPayloadSeconds, old.generation)
    assertEquals(30L, tracker.onTick(50_000L)!!.serverPayloadSeconds)
    assertEquals(20_000L, tracker.lastServerSyncTime)
  }

  private fun progress(id: String, itemId: String) = LocalMediaProgress(
    id, id, null, 100.0, 0.4, 40.0, false, null, null, 5_000L, 1_000L,
    null, "a", "http://test.invalid", "user-a", itemId, null
  )

  private fun config(id: String, address: String) = ServerConnectionConfig(
    id, 0, "Synthetic $id", address, "2.25.0", "user-$id", "test-$id", "token-$id", null
  )

  private fun server(handler: (Request) -> Response): TestServer = TestServer(handler).also { servers.add(it) }

  private data class Request(val path: String, val authorization: String, val body: String)
  private data class Response(val body: String, val code: Int = 200)

  /** Tiny loopback fixture; no dependency or traffic to a real server is needed. */
  private class TestServer(private val handler: (Request) -> Response) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val address = "http://127.0.0.1:${listener.localPort}"
    val requests = CopyOnWriteArrayList<Request>()
    private val accepting = thread(isDaemon = true, name = "OfflineSyncTestServer") {
      while (!listener.isClosed) {
        val socket = try { listener.accept() } catch (_: java.io.IOException) { break }
        workers.execute {
          socket.use {
            it.soTimeout = 10_000
            val reader = it.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val body = CharArray(headers["content-length"]?.toInt() ?: 0)
            var read = 0
            while (read < body.size) {
              val count = reader.read(body, read, body.size - read)
              check(count > 0)
              read += count
            }
            val request = Request(path, headers["authorization"].orEmpty(), String(body))
            requests.add(request)
            val response = handler(request)
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val output = it.getOutputStream()
            output.write("HTTP/1.1 ${response.code} Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
          }
        }
      }
    }

    override fun close() {
      listener.close()
      workers.shutdownNow()
      accepting.join(1_000)
      check(workers.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  companion object {
    private const val USER = """{"id":"user-a","username":"test","mediaProgress":[]}"""
  }
}
