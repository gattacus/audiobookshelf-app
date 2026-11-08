package com.audiobookshelf.app.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Keep truncated bodies, transport failures and temporary responses distinct from rejected credentials. */
internal class TokenRefreshRequest(private val client: OkHttpClient) {
  sealed interface Result {
    data class Tokens(val access: String, val refresh: String) : Result
    data class Rejected(val status: Int) : Result
    data class Transient(val reason: String) : Result
  }

  fun execute(address: String, refreshToken: String, complete: (Result) -> Unit) {
    val request = try {
      Request.Builder().url("$address/auth/refresh")
        .header("x-refresh-token", refreshToken)
        .header("Content-Type", "application/json")
        .post(ByteArray(0).toRequestBody()).build()
    } catch (e: Exception) {
      complete(Result.Transient("Could not create refresh request: ${e.javaClass.simpleName}"))
      return
    }
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        complete(Result.Transient("Refresh transport failed: ${e.javaClass.simpleName}"))
      }

      override fun onResponse(call: Call, response: Response) {
        val result = response.use {
          when {
            it.code == 401 || it.code == 403 -> Result.Rejected(it.code)
            !it.isSuccessful -> Result.Transient("Refresh returned HTTP ${it.code}")
            else -> try {
              val user = mapper.readTree(it.body?.string().orEmpty())?.get("user")
              val access = user?.get("accessToken")?.takeIf { node -> node.isTextual }?.asText().orEmpty()
              val refresh = user?.get("refreshToken")?.takeIf { node -> node.isTextual }?.asText().orEmpty()
              if (access.isBlank()) Result.Transient("Refresh response had no access token")
              else Result.Tokens(access, refresh.ifEmpty { refreshToken })
            } catch (e: Exception) {
              Result.Transient("Could not read refresh response: ${e.javaClass.simpleName}")
            }
          }
        }
        // Callback errors are not parse errors and must not result in a second completion.
        complete(result)
      }
    })
  }

  companion object { private val mapper = jacksonObjectMapper() }
}
