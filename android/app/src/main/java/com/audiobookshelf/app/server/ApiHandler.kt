package com.audiobookshelf.app.server

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.AndroidAutoShelfResponse
import com.audiobookshelf.app.media.LocalProgressReconciler
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.media.OfflineSessionCleanup
import com.audiobookshelf.app.media.OfflineSyncCoordinator
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.models.User
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.listening.ListeningIdentity
import com.audiobookshelf.app.listening.ProgressStatusStore
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.managers.SecureStorage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiHandler(var ctx:Context) {
  val tag = "ApiHandler"

  companion object {
    // For sending data back to the Webview frontend
    lateinit var absDatabaseNotifyListeners:(String, JSObject) -> Unit
    internal val offlineSyncCoordinator = OfflineSyncCoordinator()

    fun checkAbsDatabaseNotifyListenersInitted():Boolean {
      return ::absDatabaseNotifyListeners.isInitialized
    }
  }

  private var defaultClient = OkHttpClient()
  private var pingClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
  private var secureStorage = SecureStorage(ctx)

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class LocalSessionSyncResult(val id:String, val success:Boolean, val progressSynced:Boolean?, val error:String?)
  data class LocalSessionsSyncResponsePayload(val results:List<LocalSessionSyncResult>)

  private fun getRequest(endpoint:String, httpClient:OkHttpClient?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token

    try {
      val request = Request.Builder()
        .url("${address}$endpoint").addHeader("Authorization", "Bearer $token")
        .build()
      makeRequest(request, httpClient, cb, config)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun postRequest(endpoint:String, payload: JSObject?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload?.toString()?.toRequestBody(mediaType) ?: EMPTY_REQUEST
    val requestUrl = "${address}$endpoint"
    Log.d(tag, "postRequest to $requestUrl")
    try {
      val request = Request.Builder().post(requestBody)
        .url(requestUrl).addHeader("Authorization", "Bearer ${token}")
        .build()
      makeRequest(request, null, cb, config)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun patchRequest(endpoint:String, payload: JSObject, config:ServerConnectionConfig? = null, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    try {
      val request = Request.Builder().patch(requestBody)
        .url("${config?.address ?: DeviceManager.serverAddress}$endpoint").addHeader("Authorization", "Bearer ${config?.token ?: DeviceManager.token}")
        .build()
      makeRequest(request, null, cb, config)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun makeRequest(request:Request, httpClient:OkHttpClient?, cb: (JSObject) -> Unit, config:ServerConnectionConfig? = null) {
    val client = httpClient ?: defaultClient

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()

        val jsobj = JSObject()
        jsobj.put("error", "Failed to connect")
        cb(jsobj)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (it.code == 401) {
            // Handle 401 Unauthorized by attempting token refresh
            AbsLogger.info(tag, "makeRequest: 401 Unauthorized for request to \"${request.url}\" - attempt token refresh")
            handleTokenRefresh(request, httpClient, cb, config)
            return
          }

          if (!it.isSuccessful) {
            val jsobj = JSObject()
            jsobj.put("error", "Unexpected code $response")
            cb(jsobj)
            return
          }

          val bodyString = it.body!!.string()
          if (bodyString == "OK") {
            cb(JSObject())
          } else {
            try {
              var jsonObj = JSObject()
              if (bodyString.startsWith("[")) {
                val array = JSArray(bodyString)
                jsonObj.put("value", array)
              } else {
                jsonObj = JSObject(bodyString)
              }
              cb(jsonObj)
            } catch(je:JSONException) {
              Log.e(tag, "Invalid JSON response ${je.localizedMessage} from body $bodyString")
              val jsobj = JSObject()
              jsobj.put("error", "Invalid response body")
              cb(jsobj)
            }
          }
        }
      }
    })
  }

  /**
   * Handles token refresh when a 401 Unauthorized response is received
   * This function will:
   * 1. Get the refresh token from secure storage for the current server connection
   * 2. Make a request to /auth/refresh endpoint with the refresh token
   * 3. Update the stored tokens with the new access token
   * 4. Retry the original request with the new access token
   * 5. If refresh fails, fail the request ([refreshAuthTokens] owns clearing the session)
   *
   * @param originalRequest The original request that failed with 401
   * @param httpClient The HTTP client to use for the request
   * @param callback The callback to return the response
   */
  private fun handleTokenRefresh(originalRequest: Request, httpClient: OkHttpClient?, callback: (JSObject) -> Unit, config: ServerConnectionConfig? = null) {
    val serverConnectionConfigId = config?.id ?: DeviceManager.serverConnectionConfigId
    refreshAuthTokens(serverConnectionConfigId, httpClient) { result ->
      if (result is RefreshResult.Success) {
        retryOriginalRequest(originalRequest, result.accessToken, httpClient, callback)
      } else {
        callback(JSObject().put("error", "Authentication failed - login again"))
      }
    }
  }

  sealed interface RefreshResult {
    data class Success(val accessToken: String) : RefreshResult

    /** The server rejected the refresh token, so the session has already been cleared. */
    data object Rejected : RefreshResult

    /** The refresh could not be completed. Credentials are untouched and the caller may retry. */
    data object Transient : RefreshResult
  }

  /** Refreshes tokens for a specific saved server, including downloads queued while another server is active. */
  fun refreshAuthTokens(
          serverConnectionConfigId: String,
          httpClient: OkHttpClient? = null,
          onResult: (RefreshResult) -> Unit
  ) {
    val config = DeviceManager.getServerConnectionConfig(serverConnectionConfigId)
    val refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId)
    if (config == null || refreshToken.isNullOrEmpty()) {
      AbsLogger.error(tag, "No refresh token or server configuration for $serverConnectionConfigId")
      handleRefreshRejected(serverConnectionConfigId)
      onResult(RefreshResult.Rejected)
      return
    }
    val request = try {
      Request.Builder()
              .url("${config.address}/auth/refresh")
              .addHeader("x-refresh-token", refreshToken)
              .addHeader("Content-Type", "application/json")
              .post(EMPTY_REQUEST)
              .build()
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not create refresh request for ${config.name}: ${e.message}")
      onResult(RefreshResult.Transient)
      return
    }
    (httpClient ?: defaultClient).newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        AbsLogger.error(tag, "Token refresh failed for ${config.name}: ${e.message}")
        onResult(RefreshResult.Transient)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!it.isSuccessful) {
            AbsLogger.error(tag, "Token refresh returned ${it.code} for ${config.name}")
            if (it.code != 401 && it.code != 403) {
              onResult(RefreshResult.Transient)
              return
            }
            handleRefreshRejected(serverConnectionConfigId)
            onResult(RefreshResult.Rejected)
            return
          }
          try {
            val user = JSONObject(it.body!!.string()).optJSONObject("user")
            val accessToken = user?.optString("accessToken").orEmpty()
            if (accessToken.isEmpty()) {
              AbsLogger.error(tag, "Refresh response had no access token for ${config.name}")
              onResult(RefreshResult.Transient)
              return
            }
            updateTokens(accessToken, user?.optString("refreshToken").orEmpty().ifEmpty { refreshToken }, serverConnectionConfigId)
            onResult(RefreshResult.Success(accessToken))
          } catch (e: Exception) {
            AbsLogger.error(tag, "Could not parse refresh response for ${config.name}: ${e.message}")
            onResult(RefreshResult.Transient)
          }
        }
      }
    })
  }

  /**
   * Clears only the server that rejected the refresh token; queued downloads can target a non-active server.
   *
   * Only call this when the server explicitly rejected the refresh token. Transient failures should not log the user out
   */
  private fun handleRefreshRejected(serverConnectionConfigId: String) {
    // Must not throw: callers still have to report the refresh result to an in-flight request or download.
    try {
      secureStorage.removeRefreshToken(serverConnectionConfigId)
      if (DeviceManager.serverConnectionConfigId != serverConnectionConfigId) return
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      if (checkAbsDatabaseNotifyListenersInitted()) {
        absDatabaseNotifyListeners(
                "onTokenRefreshFailure",
                JSObject().put("error", "Token refresh failed").put("serverConnectionConfigId", serverConnectionConfigId))
      }
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not clear session for $serverConnectionConfigId: ${e.message}")
    }
  }

  /**
   * Updates the stored tokens with new access and refresh tokens
   *
   * @param newAccessToken The new access token
   * @param newRefreshToken The new refresh token (or existing one if not provided)
   */
  private fun updateTokens(newAccessToken: String, newRefreshToken: String, serverConnectionConfigId: String) {
    try {
      // Update the refresh token in secure storage if it's new
      if (newRefreshToken != secureStorage.getRefreshToken(serverConnectionConfigId)) {
        secureStorage.storeRefreshToken(serverConnectionConfigId, newRefreshToken)
        Log.d(tag, "updateTokens: Updated refresh token in secure storage")
      }

      // The refreshed connection may be queued in the downloader rather than currently active.
      DeviceManager.getServerConnectionConfig(serverConnectionConfigId)?.let { config ->
        config.token = newAccessToken
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
        Log.d(tag, "updateTokens: Updated access token in server connection config")
      }

      // Send access token to Webview frontend
      if (DeviceManager.serverConnectionConfigId == serverConnectionConfigId && checkAbsDatabaseNotifyListenersInitted()) {
        val tokenJsObject = JSObject()
        tokenJsObject.put("accessToken", newAccessToken)
        absDatabaseNotifyListeners("onTokenRefresh", tokenJsObject)
      } else {
        // Can happen if Webview is never run
        Log.i(tag, "AbsDatabaseNotifyListeners is not initialized so cannot send new access token")
      }
      AbsLogger.info(tag, "updateTokens: Successfully refreshed auth tokens for server ${DeviceManager.serverConnectionConfigString}")
    } catch (e: Exception) {
      Log.e(tag, "updateTokens: Failed to update tokens", e)
      AbsLogger.error(tag, "updateTokens: Failed to refresh auth tokens for server ${DeviceManager.serverConnectionConfigString} (error: ${e.message})")
    }
  }

  /**
   * Retries the original request with the new access token
   *
   * @param originalRequest The original request to retry
   * @param newAccessToken The new access token to use
   * @param httpClient The HTTP client to use
   * @param callback The callback to return the response
   */
  private fun retryOriginalRequest(originalRequest: Request, newAccessToken: String, httpClient: OkHttpClient?, callback: (JSObject) -> Unit) {
    try {
      // Create a new request with the updated authorization header
      val newRequest = originalRequest.newBuilder()
        .removeHeader("Authorization")
        .addHeader("Authorization", "Bearer $newAccessToken")
        .build()

      Log.d(tag, "retryOriginalRequest: Retrying request to ${newRequest.url}")

      // Make the retry request
      val client = httpClient ?: defaultClient
      client.newCall(newRequest).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.e(tag, "retryOriginalRequest: Failed to retry request", e)
          AbsLogger.error(tag, "retryOriginalRequest: Failed to retry request after token refresh for server ${DeviceManager.serverConnectionConfigString} (error: ${e.message})")
          val errorObj = JSObject()
          errorObj.put("error", "Failed to retry request after token refresh")
          callback(errorObj)
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!it.isSuccessful) {
              Log.e(tag, "retryOriginalRequest: Retry request failed with status ${it.code}")
              AbsLogger.error(tag, "retryOriginalRequest: Retry request failed with status ${it.code} for server ${DeviceManager.serverConnectionConfigString}")
              val errorObj = JSObject()
              errorObj.put("error", "Retry request failed with status ${it.code}")
              callback(errorObj)
              return
            }

            val bodyString = it.body!!.string()
            if (bodyString == "OK") {
              callback(JSObject())
            } else {
              try {
                var jsonObj = JSObject()
                if (bodyString.startsWith("[")) {
                  val array = JSArray(bodyString)
                  jsonObj.put("value", array)
                } else {
                  jsonObj = JSObject(bodyString)
                }
                callback(jsonObj)
              } catch(je:JSONException) {
                Log.e(tag, "retryOriginalRequest: Invalid JSON response ${je.localizedMessage} from body $bodyString")
                val errorObj = JSObject()
                errorObj.put("error", "Invalid response body")
                callback(errorObj)
              }
            }
          }
        }
      })

    } catch (e: Exception) {
      Log.e(tag, "retryOriginalRequest: Unexpected error during retry", e)
      AbsLogger.error(tag, "retryOriginalRequest: Unexpected error during retry for server ${DeviceManager.serverConnectionConfigString}")
      val errorObj = JSObject()
      errorObj.put("error", "Failed to retry request")
      callback(errorObj)
    }
  }

  fun getCurrentUser(config:ServerConnectionConfig? = null, cb: (User?) -> Unit) {
    getRequest("/api/me", null, config) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getCurrentUser Failed")
        cb(null)
      } else {
        val user = jacksonMapper.readValue<User>(it.toString())
        cb(user)
      }
    }
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonMapper
    getRequest("/api/libraries?include=stats", null,null) {
      val libraries = mutableListOf<Library>()

      var array = JSONArray()
      if (it.has("libraries")) { // TODO: Server 2.2.9 changed to this
        array = it.getJSONArray("libraries")
      } else if (it.has("value")) {
        array = it.getJSONArray("value")
      }

      for (i in 0 until array.length()) {
        libraries.add(mapper.readValue(array.get(i).toString()))
      }
      cb(libraries)
    }
  }

  fun getLibraryPersonalized(libraryItemId:String, cb: (List<LibraryShelfType>?) -> Unit) {
    getRequest("/api/libraries/$libraryItemId/personalized", null, null) {
      val shelves = AndroidAutoShelfResponse.parse(it.toString(), jacksonMapper)
      if (shelves == null) Log.w(tag, "Personalized library shelves request failed or returned an invalid response")
      cb(shelves)
    }
  }

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem?) -> Unit) {
    getRequest("/api/items/$libraryItemId?expanded=1", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItem Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, config: ServerConnectionConfig? = null, cb: (LibraryItem?) -> Unit) {
    var requestUrl = "/api/items/$libraryItemId?expanded=1&include=progress"
    if (!episodeId.isNullOrEmpty()) requestUrl += "&episode=$episodeId"
    getRequest(requestUrl, null, config) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItemWithProgress Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest("/api/libraries/$libraryId/items?limit=100&minified=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeries(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/series?minified=1&sort=name&limit=10000", null, null) {
      val items = mutableListOf<LibrarySeriesItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibrarySeriesItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeriesItems(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting items for series")
    val seriesIdBase64 = Base64.encodeToString(seriesId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?minified=1&sort=media.metadata.title&filter=series.${seriesIdBase64}&limit=1000", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibraryAuthors(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/authors", null, null) {
      val items = mutableListOf<LibraryAuthorItem>()
      if (it.has("authors")) {
        val array = it.getJSONArray("authors")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryAuthorItem>(array.get(i).toString())
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryItemsFromAuthor(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting author items")
    val authorIdBase64 = Base64.encodeToString(authorId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?limit=1000&minified=1&filter=authors.${authorIdBase64}&sort=media.metadata.title&collapseseries=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          if (item.collapsedSeries != null) {
            item.collapsedSeries?.libraryId = libraryId
          }
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryCollections(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    Log.d(tag, "Getting collections")
    getRequest("/api/libraries/$libraryId/collections?minified=1&sort=name&limit=1000", null, null) {
      val items = mutableListOf<LibraryCollection>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryCollection>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getSearchResults(libraryId:String, queryString:String, cb: (LibraryItemSearchResultType?) -> Unit) {
    Log.d(tag, "Doing search for library $libraryId")
    getRequest("/api/libraries/$libraryId/search?q=$queryString", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getSearchResults Failed")
        cb(null)
      } else {
        val librarySearchResults = jacksonMapper.readValue<LibraryItemSearchResultType>(it.toString())
        cb(librarySearchResults)
      }
    }
  }

  fun getAllItemsInProgress(cb: (List<ItemInProgress>) -> Unit) {
    getRequest("/api/me/items-in-progress", null, null) {
      val items = mutableListOf<ItemInProgress>()
      if (it.has("libraryItems")) {
        val array = it.getJSONArray("libraryItems")
        for (i in 0 until array.length()) {
          val jsobj = array.get(i) as JSONObject
          val itemInProgress = ItemInProgress.makeFromServerObject(jsobj, jacksonMapper)
          items.add(itemInProgress)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, episodeId:String?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(playItemRequestPayload))

    val endpoint = if (episodeId.isNullOrEmpty()) "/api/items/$libraryItemId/play" else "/api/items/$libraryItemId/play/$episodeId"
    postRequest(endpoint, payload, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "Play Library Item Failed")
        cb(null)
      } else {
        it.put("serverConnectionConfigId", DeviceManager.serverConnectionConfig?.id)
        it.put("serverAddress", DeviceManager.serverAddress)
        val playbackSession = jacksonMapper.readValue<PlaybackSession>(it.toString())
        cb(playbackSession)
      }
    }
  }

  fun sendProgressSync(sessionId:String, syncData: MediaProgressSyncData, cb: (Boolean, String?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(syncData))
    val config = DeviceManager.serverConnectionConfig?.copy()
    val session = DeviceManager.dbManager.getPlaybackSession(sessionId)?.clone()
    val identity = session?.let { ListeningIdentity.from(it) }
    if (identity != null && (config?.id != identity.configId || config.userId != identity.userId)) return cb(false, "Account changed")
    val token = identity?.let { ProgressStatusStore.beginSnapshot(it, session.updatedAt, session.currentTime, session.progress >= 0.99) }
    postRequest("/api/session/$sessionId/sync", payload, config) {
      if (identity != null && token != null && config != null && isCurrentConfig(config))
        ProgressStatusStore.complete(identity, token, syncData.currentTime, session.progress >= 0.99, it.getString("error").isNullOrEmpty(), it.getString("error"))
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  private fun createPartialPlaybackSession(playbackSession: PlaybackSession): ObjectNode {
    val json = jacksonMapper.createObjectNode()
    json.put("id", playbackSession.id)
    json.put("userId", playbackSession.userId)
    json.put("libraryItemId", playbackSession.libraryItemId)
    json.put("episodeId", playbackSession.episodeId)
    json.put("mediaType", playbackSession.mediaType)
    json.put("displayTitle", playbackSession.displayTitle)
    json.put("displayAuthor", playbackSession.displayAuthor)
    json.put("duration", playbackSession.duration)
    json.put("playMethod", playbackSession.playMethod)
    json.put("startedAt", playbackSession.startedAt)
    json.put("updatedAt", playbackSession.updatedAt)
    json.put("timeListening", playbackSession.timeListening)
    json.put("currentTime", playbackSession.currentTime)
    json.put("mediaPlayer", playbackSession.mediaPlayer)
    return json
  }

  fun sendLocalProgressSync(playbackSession:PlaybackSession, cb: (Boolean, String?) -> Unit) {
    val partialSession = createPartialPlaybackSession(playbackSession)
    partialSession.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(playbackSession.deviceInfo))
    val config = DeviceManager.serverConnectionConfig?.copy()
    val identity = ListeningIdentity.from(playbackSession)
    if (config?.id != identity.configId || config.userId != identity.userId) return cb(false, "Account changed")
    val token = ProgressStatusStore.beginSnapshot(identity, playbackSession.updatedAt, playbackSession.currentTime, playbackSession.progress >= 0.99)
    postRequest("/api/session/local", JSObject(partialSession.toString()), config) {
      if (config != null && isCurrentConfig(config)) ProgressStatusStore.complete(identity, token, playbackSession.currentTime, playbackSession.progress >= 0.99, it.getString("error").isNullOrEmpty(), it.getString("error"))
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  fun updateMediaProgress(libraryItemId:String,episodeId:String?,updatePayload:JSObject, config:ServerConnectionConfig? = null, cb: (Boolean) -> Unit) {
    Log.d(tag, "updateMediaProgress $libraryItemId $episodeId $updatePayload")
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"
    patchRequest(endpoint,updatePayload,config) {
      val ok = it.getString("error").isNullOrEmpty()
      Log.d(tag, "updateMediaProgress patched progress ok=$ok")
      cb(ok)
    }
  }

  fun getMediaProgress(libraryItemId:String, episodeId:String?, serverConnectionConfig:ServerConnectionConfig?, cb: (MediaProgress?) -> Unit) {
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"

    // TODO: Using ping client here allows for shorter timeout (3 seconds), maybe rename or make diff client for requests requiring quicker response
    getRequest(endpoint, pingClient, serverConnectionConfig) {
      if (it.has("error")) {
        Log.e(tag, "getMediaProgress: Failed to get progress")
        cb(null)
      } else {
        val progress = jacksonMapper.readValue<MediaProgress>(it.toString())
        cb(progress)
      }
    }
  }

  fun getPlaybackSession(playbackSessionId:String, cb: (PlaybackSession?) -> Unit) {
    Log.d(tag, "getPlaybackSession for $playbackSessionId for server ${DeviceManager.serverAddress}")
    val endpoint = "/api/session/$playbackSessionId"
    getRequest(endpoint, null, null) {
      val err = it.getString("error")
      if (!err.isNullOrEmpty()) {
        cb(null)
      } else {
        cb(jacksonMapper.readValue<PlaybackSession>(it.toString()))
      }
    }
  }

  fun pingServer(config:ServerConnectionConfig, cb: (Boolean) -> Unit) {
    Log.d(tag, "pingServer: Pinging ${config.address}")
    getRequest("/ping", pingClient, config) {
      val success = it.getString("success")
      if (success.isNullOrEmpty()) {
        Log.d(tag, "pingServer: Ping ${config.address} Failed")
        cb(false)
      } else {
        Log.d(tag, "pingServer: Ping ${config.address} Successful")
        cb(true)
      }
    }
  }

  fun closePlaybackSession(playbackSessionId:String, config:ServerConnectionConfig?, cb: (Boolean) -> Unit) {
    Log.d(tag, "closePlaybackSession: playbackSessionId=$playbackSessionId")
    postRequest("/api/session/$playbackSessionId/close", null, config) {
      cb(true)
    }
  }

  fun authorize(config:ServerConnectionConfig, cb: (MutableList<MediaProgress>?) -> Unit) {
    Log.d(tag, "authorize: Authorizing ${config.address}")
    postRequest("/api/authorize", JSObject(), config) {
      val error = it.getString("error")
      if (!error.isNullOrEmpty()) {
        Log.d(tag, "authorize: Authorize ${config.address} Failed: $error")
        cb(null)
      } else {
        val mediaProgressList:MutableList<MediaProgress> = mutableListOf()
        val user = it.getJSObject("user")
        val mediaProgress = user?.getJSONArray("mediaProgress") ?: JSONArray()
        for (i in 0 until mediaProgress.length()) {
          val mediaProg = jacksonMapper.readValue<MediaProgress>(mediaProgress.getJSONObject(i).toString())
          mediaProgressList.add(mediaProg)
        }
        Log.d(tag, "authorize: Authorize ${config.address} Successful")
        cb(mediaProgressList)
      }
    }
  }

  fun sendSyncLocalSessions(playbackSessions:List<PlaybackSession>, config:ServerConnectionConfig? = null, cb: (Boolean, String?) -> Unit) {
    val validSessions = OfflineSessionCleanup.validSessions(playbackSessions)
    if (validSessions.isEmpty()) return cb(true, null)

    @SuppressLint("HardwareIds")
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    val deviceInfo = DeviceInfo(deviceId, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)

    val json = jacksonMapper.createObjectNode()
    json.putArray("sessions").addAll(validSessions.map(::createPartialPlaybackSession))
    json.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(deviceInfo))
    AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Sending ${validSessions.size} saved local playback sessions to server (${DeviceManager.serverConnectionConfigName})")

    val sentConfig = config ?: DeviceManager.serverConnectionConfig?.copy()
    val tokens = validSessions.associate { it.id to ProgressStatusStore.beginSnapshot(ListeningIdentity.from(it), it.updatedAt, it.currentTime, it.progress >= 0.99) }
    postRequest("/api/session/local-all", JSObject(json.toString()), sentConfig) {
      if (sentConfig == null || !isCurrentConfig(sentConfig)) return@postRequest cb(false, "Account changed")
      if (!it.getString("error").isNullOrEmpty()) {
        AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync local sessions. (${it.getString("error")})")
        validSessions.forEach { session -> ProgressStatusStore.complete(ListeningIdentity.from(session), tokens.getValue(session.id), session.currentTime, session.progress >= 0.99, false, "Session upload failed") }
        cb(false, it.getString("error"))
      } else {
        val response = jacksonMapper.readValue<LocalSessionsSyncResponsePayload>(it.toString())
        response.results.forEach { localSessionSyncResult ->
          Log.d(tag, "Synced session result ${localSessionSyncResult.id}|${localSessionSyncResult.progressSynced}|${localSessionSyncResult.success}")

          validSessions.find { ps -> ps.id == localSessionSyncResult.id }?.let { session ->
            ProgressStatusStore.complete(ListeningIdentity.from(session), tokens.getValue(session.id), session.currentTime, session.progress >= 0.99,
              localSessionSyncResult.success && localSessionSyncResult.progressSynced == true,
              if (localSessionSyncResult.success) "Progress awaiting verification" else "Session upload failed")
            if (localSessionSyncResult.progressSynced == true) {
              val syncResult = SyncResult(true, true, "Progress synced on server")
              MediaEventManager.saveEvent(session, syncResult)

              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server, server progress was updated for item ${session.mediaItemId}")
            } else if (!localSessionSyncResult.success) {
              AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync session \"${session.displayTitle}\" with server. Error: ${localSessionSyncResult.error}")
            } else {
              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server. Server progress was up-to-date for item ${session.mediaItemId}")
            }
          }
        }

        val currentStored = DeviceManager.dbManager.getPlaybackSessions().associateBy { ps -> ps.id }
        val removableIds = OfflineSessionCleanup.idsToRemoveAfterBatchSync(
          validSessions,
          response.results,
          currentStored,
          MediaProgressSyncer.activePlaybackSession
        )
        removableIds.forEach { sessionId ->
          DeviceManager.dbManager.removePlaybackSession(sessionId)
        }
        AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Removed ${removableIds.size}/${validSessions.size} synced sessions from local db.")
        val allOk = validSessions.all { sent -> response.results.any { it.id == sent.id && it.success } }
        cb(allOk, if (allOk) null else "Some listening sessions could not be uploaded")
      }
    }
  }

  fun syncLocalMediaProgressForUser(config:ServerConnectionConfig? = DeviceManager.serverConnectionConfig?.copy(), identity: ListeningIdentity? = null, cb: (Boolean, String?) -> Unit) {
    if (config == null || !isCurrentConfig(config)) return cb(false, "Account changed or unavailable")
    getCurrentUser(config) { user ->
      if (!isCurrentConfig(config)) return@getCurrentUser cb(false, "Account changed")
      if (user == null) return@getCurrentUser cb(false, "Could not read server progress")
      val locals = DeviceManager.dbManager.getAllLocalMediaProgress().filter {
        it.serverConnectionConfigId == config.id && it.serverUserId == config.userId && (identity == null || ListeningIdentity.from(it) == identity)
      }
      val pending = DeviceManager.dbManager.getPlaybackSessions().filter { it.serverConnectionConfigId == config.id }
      locals.forEach { ProgressStatusStore.seed(it) }
      val plan = LocalProgressReconciler.reconcile(locals, user.mediaProgress, pending)
      plan.updatedLocalItems.forEach { DeviceManager.dbManager.saveLocalMediaProgress(it) }
      // A readback verifies exact content, not merely a server timestamp or successful session upload.
      val candidates = ProgressStatusStore.identities().filter { it.configId == config.id && it.userId == config.userId && (identity == null || it == identity) }
      candidates.forEach { id ->
        val receipt = ProgressStatusStore.get(id)
        val remote = user.mediaProgress.find { it.libraryItemId == id.itemId && it.episodeId.orEmpty() == id.episodeId.orEmpty() }
        if (remote != null && receipt.savedAt > 0 && remote.currentTime == receipt.position && remote.isFinished == receipt.finished) {
          ProgressStatusStore.complete(id, receipt.revision, remote.currentTime, remote.isFinished, true)
        }
      }
      if (plan.serverPatches.isEmpty()) return@getCurrentUser cb(true, null)
      val remaining = AtomicInteger(plan.serverPatches.size)
      val failures = AtomicInteger(0)
      plan.serverPatches.forEach { patch ->
        val id = ListeningIdentity(config.id, config.userId, patch.libraryItemId, patch.episodeId)
        val before = locals.find { ListeningIdentity.from(it) == id }
        val token = ProgressStatusStore.beginSnapshot(id, patch.lastUpdate, patch.currentTime ?: before?.currentTime ?: 0.0, patch.isFinished ?: before?.isFinished ?: false)
        val payload = JSObject()
        patch.isFinished?.let { payload.put("isFinished", it) }
        patch.currentTime?.let { payload.put("currentTime", it) }
        patch.progress?.let { payload.put("progress", it) }
        patch.finishedAt?.let { payload.put("finishedAt", it) }
        patch.startedAt?.let { payload.put("startedAt", it) }
        patch.ebookLocation?.let { payload.put("ebookLocation", it) }
        patch.ebookProgress?.let { payload.put("ebookProgress", it) }
        payload.put("lastUpdate", patch.lastUpdate)
        if (!isCurrentConfig(config)) {
          failures.incrementAndGet()
          if (remaining.decrementAndGet() == 0) cb(false, "Account changed")
        } else updateMediaProgress(patch.libraryItemId, patch.episodeId, payload, config) { ok ->
          val valid = ok && isCurrentConfig(config)
          if (!valid) failures.incrementAndGet()
          val receipt = ProgressStatusStore.get(id)
          ProgressStatusStore.complete(id, token, patch.currentTime ?: receipt.position, patch.isFinished ?: receipt.finished, valid)
          if (remaining.decrementAndGet() == 0) cb(failures.get() == 0, if (failures.get() == 0) null else "Some progress updates failed")
        }
      }
    }
  }

  private fun isCurrentConfig(config: ServerConnectionConfig): Boolean {
    val current = DeviceManager.serverConnectionConfig ?: DeviceManager.dbManager.getDeviceData().getLastServerConnectionConfig() ?: return false
    return current.id == config.id && current.address == config.address && current.userId == config.userId
  }

  fun syncLocalSessionsAndProgress(cb: (Boolean, String?) -> Unit) = syncLocalSessionsAndProgress(null, cb)

  fun syncLocalSessionsAndProgress(identity: ListeningIdentity? = null, cb: (Boolean, String?) -> Unit = { _, _ -> }) {
    // Keep every phase (including token refresh) attached to this account, not mutable global state.
    val config = (DeviceManager.serverConnectionConfig ?: DeviceManager.dbManager.getDeviceData().getLastServerConnectionConfig())?.copy()
    val configId = config?.id
    offlineSyncCoordinator.sync(
      serverConfigId = if (identity == null) configId else "$configId:${identity.key}",
      pushSessions = { pushCb ->
        val savedSessions = DeviceManager.dbManager.getPlaybackSessions()
          .filter { it.serverConnectionConfigId == configId && it.userId == config?.userId && (identity == null || ListeningIdentity.from(it) == identity) }
          .map { it.clone() }
        if (savedSessions.isNotEmpty()) {
          sendSyncLocalSessions(savedSessions, config, pushCb)
        } else {
          AbsLogger.info("ApiHandler", "syncLocalSessionsAndProgress: No saved local playback sessions to send to server.")
          pushCb(true, null)
        }
      },
      reconcileProgress = { reconcileCb ->
        syncLocalMediaProgressForUser(config, identity, reconcileCb)
      },
      onComplete = cb
    )
  }
}
