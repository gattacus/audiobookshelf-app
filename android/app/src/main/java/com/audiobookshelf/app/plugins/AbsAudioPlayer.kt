package com.audiobookshelf.app.plugins

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.ItemPlaybackWidget
import com.audiobookshelf.app.WidgetTarget
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.player.CastManager
import com.audiobookshelf.app.player.PlayerListener
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.PodcastPlaybackPreferences
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.exoplayer2.Player
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler
  var castManager:CastManager? = null

  lateinit var playerNotificationService: PlayerNotificationService

  private var isCastAvailable:Boolean = false

  // Track foreground state to avoid flooding WebView with events while backgrounded
  private var isInForeground: Boolean = true
  private val pendingPlaybackStateCalls = mutableListOf<PluginCall>()

  private fun podcastPreferenceKey(call: PluginCall): String? {
    val id = call.getString("libraryItemId")?.takeIf { it.isNotBlank() } ?: return null
    if (id.startsWith("local")) {
      val local = runCatching { DeviceManager.dbManager.getLocalLibraryItem(id) }.getOrNull() ?: return null
      if (local.mediaType != "podcast") return null
      return PodcastPlaybackPreferences.key(local.serverConnectionConfigId, local.libraryItemId, local.id)
    }
    return PodcastPlaybackPreferences.key(call.getString("serverConnectionConfigId"), id, null)
  }

  @PluginMethod
  fun getPodcastContinuousPlayback(call: PluginCall) {
    val key = podcastPreferenceKey(call) ?: return call.reject("Invalid podcast identity")
    call.resolve(JSObject().put("enabled", PodcastPlaybackPreferences(context).get(key) == true))
  }

  @PluginMethod
  fun setPodcastContinuousPlayback(call: PluginCall) {
    val key = podcastPreferenceKey(call) ?: return call.reject("Invalid podcast identity")
    val enabled = call.getBoolean("enabled") ?: return call.reject("Missing continuous playback choice")
    val preferences = PodcastPlaybackPreferences(context)
    preferences.set(key, enabled)
    call.getString("podcastType")?.takeIf { it == "serial" || it == "episodic" }?.let { preferences.set("$key:serial", it == "serial") }
    Handler(Looper.getMainLooper()).post {
      if (::playerNotificationService.isInitialized && !enabled) {
        val playback = playerNotificationService.continuousPodcastPlayback
        playerNotificationService.currentPlaybackSession?.let { if (playback.key(it) == key) playback.cancel() }
      }
      call.resolve(JSObject().put("enabled", enabled))
    }
  }

  @PluginMethod
  fun getPlaybackState(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      if (::playerNotificationService.isInitialized) {
        resolvePlaybackState(call)
      } else {
        pendingPlaybackStateCalls.add(call)
      }
    }
  }

  private fun resolvePlaybackState(call: PluginCall) {
    val service = playerNotificationService
    val session = service.currentPlaybackSession
    val result = JSObject()
    if (session != null) {
      result.put("session", JSObject(jacksonMapper.writeValueAsString(session)))
      result.put("isPlaying", service.currentPlayer.isPlaying)
      result.put("currentTime", service.getCurrentTimeSeconds())
      result.put("duration", session.getTotalDuration())
      result.put("playerState", when (service.currentPlayer.playbackState) {
        Player.STATE_BUFFERING -> PlayerState.BUFFERING.name
        Player.STATE_ENDED -> PlayerState.ENDED.name
        Player.STATE_IDLE -> PlayerState.IDLE.name
        else -> PlayerState.READY.name
      })
    }
    call.resolve(result)
  }

  @PluginMethod
  fun pinItemPlaybackWidget(call: PluginCall) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      call.reject("Launcher pinning requires Android 8.0 or newer")
      return
    }
    val manager = AppWidgetManager.getInstance(mainActivity)
    val provider = ComponentName(mainActivity, ItemPlaybackWidget::class.java)
    if (!manager.isRequestPinAppWidgetSupported) {
      call.reject("This launcher does not support adding widgets from the app")
      return
    }
    val mediaType = call.getString("mediaType")
    val title = call.getString("title")
    val serverItemId = call.getString("serverItemId")?.takeIf { it.isNotBlank() }
    val localItemId = call.getString("localItemId")?.takeIf { it.isNotBlank() }
    val serverConfigId = call.getString("serverConfigId")?.takeIf { it.isNotBlank() }
    if (mediaType !in listOf("book", "podcast") || title.isNullOrBlank() ||
      (serverItemId == null && localItemId == null) || (serverItemId != null && serverConfigId == null)) {
      call.reject("Invalid widget target")
      return
    }
    val target = WidgetTarget(title, mediaType!!, call.getString("podcastType") ?: "", serverItemId, serverConfigId, localItemId,
      call.getString("episodeDates") ?: "{}", call.getBoolean("hasCover") == true)
    val callbackIntent = Intent(mainActivity, ItemPlaybackWidget::class.java).apply {
      action = ItemPlaybackWidget.ACTION_PINNED
      putExtra(ItemPlaybackWidget.EXTRA_TARGET, target.toJson())
    }
    val callback = PendingIntent.getBroadcast(mainActivity, SystemClock.uptimeMillis().toInt(), callbackIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    val requested = manager.requestPinAppWidget(provider, null, callback)
    if (requested) call.resolve(JSObject().put("requested", true))
    else call.reject("The launcher did not accept the widget request")
  }

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    try {
      initCastManager()
    } catch(e:Exception) {
      Log.e(tag, "initCastManager exception ${e.printStackTrace()}")
    }

    val foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.clientEventEmitter = (object : PlayerNotificationService.ClientEventEmitter {
        override fun onPlaybackSession(playbackSession: PlaybackSession) {
          notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)))
        }

        override fun onPlaybackClosed() {
          emit("onPlaybackClosed", true)
        }

        override fun onPlayingUpdate(isPlaying: Boolean) {
          emit("onPlayingUpdate", isPlaying)
        }

        override fun onMetadata(metadata: PlaybackMetadata) {
          // Skip frequent metadata updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))
        }

        override fun onSleepTimerEnded(currentPosition: Long) {
          emit("onSleepTimerEnded", currentPosition)
        }

        override fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer:Boolean) {
          // Skip sleep timer updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          val ret = JSObject()
          ret.put("value", sleepTimeRemaining)
          ret.put("isAuto", isAutoSleepTimer)
          notifyListeners("onSleepTimerSet", ret)
        }

        override fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress) {
          // Skip progress updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          notifyListeners("onLocalMediaProgressUpdate", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
        }

        override fun onPlaybackFailed(errorMessage: String) {
          emit("onPlaybackFailed", errorMessage)
        }

        override fun onMediaPlayerChanged(mediaPlayer:String) {
          emit("onMediaPlayerChanged", mediaPlayer)
        }

        override fun onProgressSyncFailing() {
          emit("onProgressSyncFailing", "")
        }

        override fun onProgressSyncSuccess() {
          emit("onProgressSyncSuccess", "")
        }

        override fun onNetworkMeteredChanged(isUnmetered:Boolean) {
          emit("onNetworkMeteredChanged", isUnmetered)
        }

        override fun onMediaItemHistoryUpdated(mediaItemHistory:MediaItemHistory) {
          notifyListeners("onMediaItemHistoryUpdated", JSObject(jacksonMapper.writeValueAsString(mediaItemHistory)))
        }

        override fun onPlaybackSpeedChanged(playbackSpeed:Float) {
          emit("onPlaybackSpeedChanged", playbackSpeed)
        }
      })

      MediaEventManager.clientEventEmitter = playerNotificationService.clientEventEmitter
      pendingPlaybackStateCalls.toList().also { pendingPlaybackStateCalls.clear() }.forEach(::resolvePlaybackState)
    }
    mainActivity.pluginCallback = foregroundServiceReady
  }

  fun emit(evtName: String, value: Any) {
    val ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  override fun handleOnDestroy() {
    castManager?.detach()
    super.handleOnDestroy()
  }

  override fun handleOnPause() {
    super.handleOnPause()
    isInForeground = false
  }

  override fun handleOnResume() {
    super.handleOnResume()
    isInForeground = true

    // Send current state to UI after resume to sync up (with small delay to let WebView fully resume)
    if (::playerNotificationService.isInitialized && playerNotificationService.currentPlaybackSession != null) {
      Handler(Looper.getMainLooper()).postDelayed({
        playerNotificationService.currentPlaybackSession?.let { session ->
          playerNotificationService.clientEventEmitter?.onPlaybackSession(session)
          playerNotificationService.clientEventEmitter?.onPlayingUpdate(playerNotificationService.currentPlayer.isPlaying)
          playerNotificationService.sendClientMetadata(PlayerState.READY)
          playerNotificationService.sleepTimerManager.sendCurrentSleepTimerState()
          playerNotificationService.mediaProgressSyncer.currentLocalMediaProgress?.let {
            playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
          }
        }
      }, 100)
    }
  }

  private fun initCastManager() {
    val googleApi = GoogleApiAvailability.getInstance()
    val statusCode = googleApi.isGooglePlayServicesAvailable(mainActivity)

    if (statusCode != ConnectionResult.SUCCESS) {
        if (statusCode == ConnectionResult.SERVICE_MISSING) {
          Log.w(tag, "initCastManager: Google Api Missing")
        } else if (statusCode == ConnectionResult.SERVICE_DISABLED) {
          Log.w(tag, "initCastManager: Google Api Disabled")
        } else if (statusCode == ConnectionResult.SERVICE_INVALID) {
          Log.w(tag, "initCastManager: Google Api Invalid")
        } else if (statusCode == ConnectionResult.SERVICE_UPDATING) {
          Log.w(tag, "initCastManager: Google Api Updating")
        } else if (statusCode == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED) {
          Log.w(tag, "initCastManager: Google Api Update Required")
        }
        return
    }

    val connListener = object: CastManager.ChromecastListener() {
      override fun onReceiverAvailableUpdate(available: Boolean) {
        Log.d(tag, "ChromecastListener: CAST Receiver Update Available $available")
        isCastAvailable = available
        emit("onCastAvailableUpdate", available)
      }

      override fun onSessionRejoin(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionRejoin")
      }

      override fun onMediaLoaded(jsonMedia: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onMediaLoaded")
      }

      override fun onMediaUpdate(jsonMedia: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onMediaUpdate")
      }

      override fun onSessionUpdate(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionUpdate")
      }

      override fun onSessionEnd(jsonSession: JSONObject?) {
        Log.d(tag, "ChromecastListener: CAST onSessionEnd")
      }

      override fun onMessageReceived(p0: CastDevice, p1: String, p2: String) {
        Log.d(tag, "ChromecastListener: CAST onMessageReceived")
      }
    }

    castManager = CastManager(mainActivity)
    castManager?.startRouteScan(connListener)
  }

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    Handler(Looper.getMainLooper()).post { playerNotificationService.continuousPodcastPlayback.cancel() }
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val episodeId = call.getString("episodeId", "").toString()
    val playWhenReady = call.getBoolean("playWhenReady") == true
    val playbackRate = call.getFloat("playbackRate",1f) ?: 1f
    val startTimeOverride = call.getDouble("startTime")
    val active = playerNotificationService.currentPlaybackSession
    val sameItem = active != null && (active.libraryItemId == libraryItemId || active.localLibraryItem?.id == libraryItemId) &&
      (active.episodeId.orEmpty() == episodeId || active.localEpisodeId.orEmpty() == episodeId)
    if (startTimeOverride != null && sameItem) {
      Handler(Looper.getMainLooper()).post {
        if (playerNotificationService.currentPlaybackSession?.let { com.audiobookshelf.app.listening.ListeningIdentity.from(it) } != com.audiobookshelf.app.listening.ListeningIdentity.from(active!!)) return@post call.reject("Playback item changed")
        playerNotificationService.explicitSeek((startTimeOverride * 1000).toLong(), "history")
        call.resolve(JSObject())
      }
      return
    }


    AbsLogger.info("AbsAudioPlayer", "prepareLibraryItem: lid=$libraryItemId, startTimeOverride=$startTimeOverride, playbackRate=$playbackRate")

    if (libraryItemId.isEmpty()) {
      Log.e(tag, "Invalid call to play library item no library item id")
      return call.resolve(JSObject("{\"error\":\"Invalid request\"}"))
    }

    if (libraryItemId.startsWith("local")) { // Play local media item
      DeviceManager.dbManager.getLocalLibraryItem(libraryItemId)?.let {
        var episode: PodcastEpisode? = null
        if (episodeId.isNotEmpty()) {
          episode = (it.media as? Podcast)?.episodes?.find { ep -> ep.id == episodeId }
          if (episode == null) {
            Log.e(tag, "prepareLibraryItem: Podcast episode not found $episodeId")
            return call.resolve(JSObject("{\"error\":\"Podcast episode not found\"}"))
          }
        }
        if (!it.hasTracks(mainActivity, episode)) {
          val error = if (it.isPodcast) "This episode is not available on this device." else "No audio files found on device. Download book again to fix."
          return call.resolve(JSObject().put("error", error))
        }

        Handler(Looper.getMainLooper()).post {
          Log.d(tag, "prepareLibraryItem: Preparing Local Media item ${jacksonMapper.writeValueAsString(it)}")
          val playbackSession = it.getPlaybackSession(episode, playerNotificationService.getDeviceInfo())
            ?: return@post
          if (startTimeOverride != null) {
            Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
            playbackSession.currentTime = startTimeOverride
          }

          if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) { // If progress syncing then first stop before preparing next
            playerNotificationService.mediaProgressSyncer.stop {
              Log.d(tag, "Media progress syncer was already syncing - stopped")
              PlayerListener.lazyIsPlaying = false

              Handler(Looper.getMainLooper()).post { // TODO: This was needed again which is probably a design a flaw
                playerNotificationService.preparePlayer(
                  playbackSession,
                  playWhenReady,
                  playbackRate
                )
              }
            }
          } else {
            playerNotificationService.mediaProgressSyncer.reset()
            playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = playerNotificationService.getPlayItemRequestPayload(false)
      Handler(Looper.getMainLooper()).post {
        playerNotificationService.mediaProgressSyncer.stop {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
            if (it == null) {
              call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
            } else {
              if (startTimeOverride != null) {
                Log.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                it.currentTime = startTimeOverride
              }

              Handler(Looper.getMainLooper()).post {
                Log.d(tag, "Preparing Player playback session ${jacksonMapper.writeValueAsString(it)}")
                PlayerListener.lazyIsPlaying = false
                playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
              }
              call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
            }
          }
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val currentTime = playerNotificationService.getCurrentTimeSeconds()
      val bufferedTime = playerNotificationService.getBufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val playing = playerNotificationService.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time: Double = call.getDouble("value", 0.0) ?: 0.0 // Value in seconds, fractional
    if (!time.isFinite()) return call.reject("Invalid playback position")
    val expectedSession = call.getString("sessionId")
    Log.d(tag, "seek action to $time")
    Handler(Looper.getMainLooper()).post {
      if (expectedSession != null && playerNotificationService.currentPlaybackSession?.id != expectedSession) return@post call.reject("Playback item changed")
      playerNotificationService.explicitSeek((time * 1000L).toLong(), call.getString("source") ?: "timeline")
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.explicitSeek(playerNotificationService.getCurrentTime() + amount * 1000L, "skip") // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.explicitSeek(playerNotificationService.getCurrentTime() - amount * 1000L, "skip") // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.closePlayback()
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    val time:Long = call.getString("time", "360000")!!.toLong()
    val isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    Handler(Looper.getMainLooper()).post {
        val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
        val success:Boolean = playerNotificationService.sleepTimerManager.setManualSleepTimer(playbackSession?.id ?: "", time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    val time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.decreaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.cancelSleepTimer()
    }
    call.resolve()
  }

  @PluginMethod
  fun requestSession(call: PluginCall) {
    // Need to make sure the player service has been started
    Log.d(tag, "CAST REQUEST SESSION PLUGIN")
    call.resolve()
    if (castManager == null) {
      Log.e(tag, "Cast Manager not initialized")
      return
    }
    castManager?.requestSession(playerNotificationService, object : CastManager.RequestSessionCallback() {
      override fun onError(errorCode: Int) {
        Log.e(tag, "CAST REQUEST SESSION CALLBACK ERROR $errorCode")
      }

      override fun onCancel() {
        Log.d(tag, "CAST REQUEST SESSION ON CANCEL")
      }

      override fun onJoin(jsonSession: JSONObject?) {
        Log.d(tag, "CAST REQUEST SESSION ON JOIN")
      }
    })
  }

  @PluginMethod
  fun getIsCastAvailable(call: PluginCall) {
    val jsobj = JSObject()
    jsobj.put("value", isCastAvailable)
    call.resolve(jsobj)
  }
}
