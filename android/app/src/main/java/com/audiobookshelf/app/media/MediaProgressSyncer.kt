package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import kotlin.concurrent.schedule

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

class MediaProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler
) {
  companion object {
    @Volatile var activePlaybackSession: PlaybackSession? = null
  }

  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000L

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  internal val timeTracker = ListeningTimeTracker()
  private val lastSyncTime: Long
    get() = timeTracker.lastTickTime
  private var failedSyncs: Int = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
    set(value) {
      field = value
      activePlaybackSession = value?.clone()
    }
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

  fun start(playbackSession: PlaybackSession) {
    val isSameSession = playbackSession.id == currentSessionId
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (!isSameSession) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        timeTracker.onPauseOrReset()
        Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0
      } else {
        return
      }
    } else if (!isSameSession) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    val now = System.currentTimeMillis()
    timeTracker.start(now, isSameSession)
    currentPlaybackSession = playbackSession.clone()
    Log.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
              Handler(Looper.getMainLooper()).post() {
                if (playerNotificationService.currentPlayer.isPlaying) {
                  // Set auto sleep timer if enabled and within start/end time
                  playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last server sync time is >= 60s
                  val shouldSyncServer =
                          timeTracker.shouldSyncServer(
                                  System.currentTimeMillis(),
                                  PlayerNotificationService.isUnmeteredNetwork,
                                  METERED_CONNECTION_SYNC_INTERVAL
                          )

                  val currentTime = playerNotificationService.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      Log.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
  }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      return cb()
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      sync(true, currentTime) { syncResult ->
        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val pausedSession = currentPlaybackSession
    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        if (currentPlaybackSession !== pausedSession) return@sync cb()
        timeTracker.onPauseOrReset()
        Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      }
    } else {
      timeTracker.onPauseOrReset()
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult ->
      reset()

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.finishedEvent(playbackSession, syncResult)
      }

      cb()
    }
  }

  fun seek() {
    currentPlaybackSession?.currentTime = playerNotificationService.getCurrentTimeSeconds()
    Log.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime
      activePlaybackSession = it.clone()

      MediaEventManager.syncEvent(
              mediaProgress,
              "Received from server get media progress request while playback session open"
      )
      saveLocalProgress(it)
    }
  }

  private fun removeSessionIfNotUpdated(sessionId: String, syncedUpdatedAt: Long) {
    val stored = DeviceManager.dbManager.getPlaybackSession(sessionId)
    if (OfflineSessionCleanup.shouldRemoveAfterSingleSync(sessionId, syncedUpdatedAt, stored, currentPlaybackSession)) {
      DeviceManager.dbManager.removePlaybackSession(sessionId)
    }
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val now = System.currentTimeMillis()
    val tickStep = timeTracker.onTick(now) ?: return cb(null)

    val sessionSyncData = MediaProgressSyncData(tickStep.sessionDeltaSeconds, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(sessionSyncData)
    activePlaybackSession = currentPlaybackSession?.clone()

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      return cb(null)
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)
    val syncingSession = currentPlaybackSession
    val syncingSessionId = currentSessionId
    val syncedUpdatedAt = currentPlaybackSession?.updatedAt ?: now
    val serverSyncData = MediaProgressSyncData(tickStep.serverPayloadSeconds, currentPlaybackDuration, currentTime)

    // Save playback session to db (server linked sessions only)
    //   Sessions are removed once successfully synced with the server
    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        saveLocalProgress(it)

        Log.d(
                tag,
                "Sync local device current serverConnectionConfigId=${DeviceManager.serverConnectionConfig?.id}"
        )
        AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this
        // server
        val isConnectedToSameServer = it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId
        if (hasNetworkConnection &&
                        shouldSyncServer &&
                        !it.libraryItemId.isNullOrEmpty() &&
                        isConnectedToSameServer
        ) {
          val sessionSnapshot = it.clone()
          apiHandler.sendLocalProgressSync(sessionSnapshot) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              timeTracker.onServerSyncComplete(System.currentTimeMillis(), true, tickStep.serverPayloadSeconds, tickStep.generation)
              removeSessionIfNotUpdated(sessionSnapshot.id, syncedUpdatedAt)
            }
            if (currentPlaybackSession !== syncingSession) {
              return@sendLocalProgressSync cb(SyncResult(true, syncSuccess, errorMsg))
            }
            if (syncSuccess) {
              failedSyncs = 0
              playerNotificationService.alertSyncSuccess()
              AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${sessionSnapshot.id})")
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                playerNotificationService.alertSyncFailing() // Show alert in client
                failedSyncs = 0
              }
              AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${sessionSnapshot.id}) (${DeviceManager.serverConnectionConfigName})")
            }

            cb(SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          cb(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

      apiHandler.sendProgressSync(syncingSessionId, serverSyncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          timeTracker.onServerSyncComplete(System.currentTimeMillis(), true, tickStep.serverPayloadSeconds, tickStep.generation)
          removeSessionIfNotUpdated(syncingSessionId, syncedUpdatedAt)
        }
        if (currentPlaybackSession !== syncingSession) {
          return@sendProgressSync cb(SyncResult(true, syncSuccess, errorMsg))
        }
        if (syncSuccess) {
          AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

          failedSyncs = 0
          playerNotificationService.alertSyncSuccess()
        } else {
          failedSyncs++
          if (failedSyncs == 2) {
            playerNotificationService.alertSyncFailing() // Show alert in client
            failedSyncs = 0
          }
          AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName})")
        }
        cb(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      cb(SyncResult(false, null, null))
    }
  }

  fun completeForContinuousPlayback(endedSession: PlaybackSession) {
    val session = currentPlaybackSession?.takeIf { it.id == endedSession.id } ?: endedSession.clone()
    session.currentTime = session.getTotalDuration()
    session.updatedAt = System.currentTimeMillis()
    DeviceManager.dbManager.savePlaybackSession(session)
    if (session.isLocal) saveLocalProgress(session)
    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    reset()
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
              DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress().also { it.updateFromPlaybackSession(playbackSession) }
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        Log.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    timeTracker.onPauseOrReset()
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
  }
}
