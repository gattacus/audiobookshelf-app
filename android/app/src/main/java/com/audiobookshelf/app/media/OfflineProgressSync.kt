package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.server.ApiHandler

/**
 * Tracks local 15-second playback ticks separately from server sync intervals and pending server
 * listening time, preventing quadratic timeListening inflation when server syncs are skipped or fail.
 */
internal class ListeningTimeTracker {
  data class TickStep(
    val sessionDeltaSeconds: Long,
    val serverPayloadSeconds: Long,
    val updatedAt: Long,
    val generation: Long
  )

  var lastTickTime: Long = 0L
    private set
  var lastServerSyncTime: Long = 0L
    private set
  var pendingServerListeningTime: Long = 0L
    private set
  private var initialized: Boolean = false
  private var generation: Long = 0L

  @Synchronized
  fun start(now: Long, isSameSession: Boolean) {
    if (!isSameSession || !initialized) {
      generation++
      pendingServerListeningTime = 0L
      lastServerSyncTime = now
      initialized = true
    }
    lastTickTime = now
  }

  @Synchronized
  fun shouldSyncServer(now: Long, isUnmeteredNetwork: Boolean, meteredIntervalMs: Long = 60_000L): Boolean {
    if (!initialized) return false
    return isUnmeteredNetwork || (now - lastServerSyncTime >= meteredIntervalMs)
  }

  @Synchronized
  fun onTick(now: Long): TickStep? {
    if (!initialized || lastTickTime < 0L) return null
    val diff = now - lastTickTime
    if (diff < 1000L) return null
    val delta = diff / 1000L
    lastTickTime = now
    pendingServerListeningTime += delta
    return TickStep(
      sessionDeltaSeconds = delta,
      serverPayloadSeconds = pendingServerListeningTime,
      updatedAt = now,
      generation = generation
    )
  }

  @Synchronized
  fun onServerSyncComplete(now: Long, success: Boolean, syncedSeconds: Long, generation: Long) {
    // Responses can arrive after a different session starts, or after pause/resume of the same one.
    if (success && initialized && generation == this.generation) {
      pendingServerListeningTime = (pendingServerListeningTime - syncedSeconds).coerceAtLeast(0L)
      lastServerSyncTime = now
    }
  }

  @Synchronized
  fun onPauseOrReset() {
    generation++
    lastTickTime = 0L
    lastServerSyncTime = 0L
    pendingServerListeningTime = 0L
    initialized = false
  }
}

/**
 * Validates restored Paper playback sessions and prevents deleting failed or concurrently updated
 * sessions from Paper storage.
 */
internal object OfflineSessionCleanup {
  fun validSessions(sessions: List<PlaybackSession>?): List<PlaybackSession> =
    sessions.orEmpty().filter { session ->
      runCatching { session.id.isNotBlank() }.getOrDefault(false)
    }

  fun shouldRemoveAfterSingleSync(
    sentSessionId: String,
    sentUpdatedAt: Long,
    storedSession: PlaybackSession?,
    activeSession: PlaybackSession?
  ): Boolean {
    if (sentSessionId.isBlank()) return false
    if (storedSession != null && storedSession.updatedAt > sentUpdatedAt) return false
    if (activeSession != null && runCatching { activeSession.id == sentSessionId }.getOrDefault(false) && activeSession.updatedAt > sentUpdatedAt) {
      return false
    }
    return true
  }

  fun idsToRemoveAfterBatchSync(
    sentSessions: List<PlaybackSession>,
    results: List<ApiHandler.LocalSessionSyncResult>,
    currentStoredSessions: Map<String, PlaybackSession>,
    activeSession: PlaybackSession?
  ): Set<String> {
    val succeededIds = results.asSequence().filter { it.success }.map { it.id }.toSet()
    return validSessions(sentSessions)
      .asSequence()
      .filter { sent ->
        sent.id in succeededIds &&
          shouldRemoveAfterSingleSync(
            sent.id,
            sent.updatedAt,
            currentStoredSessions[sent.id],
            activeSession
          )
      }
      .map { it.id }
      .toSet()
  }
}

/**
 * Reconciles LocalMediaProgress with server MediaProgress after queued PlaybackSessions have been
 * pushed, ensuring offline finished toggles, ebook locations, and new offline items are patched
 * to the server while newer server progress is pulled when no newer pending session exists.
 */
internal object LocalProgressReconciler {
  data class ServerProgressPatch(
    val libraryItemId: String,
    val episodeId: String?,
    val isFinished: Boolean?,
    val currentTime: Double?,
    val progress: Double?,
    val finishedAt: Long?,
    val startedAt: Long?,
    val ebookLocation: String?,
    val ebookProgress: Double?,
    val lastUpdate: Long
  )

  data class ReconciliationPlan(
    val updatedLocalItems: List<LocalMediaProgress>,
    val serverPatches: List<ServerProgressPatch>
  )

  fun validLocalProgress(items: List<LocalMediaProgress>?): List<LocalMediaProgress> =
    items.orEmpty().filter { item ->
      runCatching { item.id.isNotBlank() }.getOrDefault(false)
    }

  fun reconcile(
    localItems: List<LocalMediaProgress>,
    serverItems: List<MediaProgress>,
    unsyncedSessions: List<PlaybackSession>
  ): ReconciliationPlan {
    val validLocals = validLocalProgress(localItems)
    val validSessions = OfflineSessionCleanup.validSessions(unsyncedSessions)
    val updatedLocals = mutableListOf<LocalMediaProgress>()
    val patches = mutableListOf<ServerProgressPatch>()

    for (local in validLocals) {
      val server = serverItems.find { local.isMatch(it) }
      val hasNewerOrEqualPendingSession = validSessions.any { session ->
        session.mediaItemId == local.mediaItemId && session.updatedAt >= local.lastUpdate
      }

      if (server != null && server.lastUpdate > local.lastUpdate) {
        val pendingSessionOutdatesServer = validSessions.any { session ->
          session.mediaItemId == local.mediaItemId && session.updatedAt > server.lastUpdate
        }
        if (!pendingSessionOutdatesServer) {
          local.updateFromServerMediaProgress(server)
          updatedLocals.add(local)
        }
      } else if (!local.libraryItemId.isNullOrBlank() && (server == null || local.lastUpdate > server.lastUpdate)) {
        val ebookChanged = !local.ebookLocation.isNullOrBlank() &&
          (server == null || local.ebookLocation != server.ebookLocation || local.ebookProgress != server.ebookProgress)
        val finishedChanged = if (server == null) local.isFinished else local.isFinished != server.isFinished
        val audioPositionChanged = !hasNewerOrEqualPendingSession &&
          (if (server == null) (local.currentTime > 0.0 || local.progress > 0.0)
          else (local.currentTime != server.currentTime || local.progress != server.progress))

        if (local.lastUpdate > 0L && (ebookChanged || finishedChanged || audioPositionChanged)) {
          val includeAudioFields = finishedChanged || audioPositionChanged
          patches.add(
            ServerProgressPatch(
              libraryItemId = local.libraryItemId!!,
              episodeId = local.episodeId?.takeIf { it.isNotBlank() },
              isFinished = if (includeAudioFields) local.isFinished else null,
              currentTime = if (includeAudioFields) local.currentTime else null,
              progress = if (includeAudioFields) local.progress else null,
              finishedAt = if (includeAudioFields) local.finishedAt else null,
              startedAt = if (includeAudioFields && local.startedAt > 0L) local.startedAt else null,
              ebookLocation = if (ebookChanged) local.ebookLocation else null,
              ebookProgress = if (ebookChanged) local.ebookProgress else null,
              lastUpdate = local.lastUpdate
            )
          )
        }
      }
    }

    return ReconciliationPlan(updatedLocals, patches)
  }
}

/**
 * Coordinates pushing queued PlaybackSessions before reconciling LocalMediaProgress, coalescing
 * concurrent invocations from WebView, Android Auto, and Homescreen Widgets.
 */
internal class OfflineSyncCoordinator {
  private val lock = Any()
  private var activeConfigId: String? = null
  private val pendingCallbacks = mutableListOf<(Boolean, String?) -> Unit>()

  fun sync(
    serverConfigId: String?,
    pushSessions: ((Boolean, String?) -> Unit) -> Unit,
    reconcileProgress: ((Boolean, String?) -> Unit) -> Unit,
    onComplete: (Boolean, String?) -> Unit
  ) {
    if (serverConfigId.isNullOrBlank()) {
      onComplete(false, "Not connected to server")
      return
    }
    synchronized(lock) {
      if (activeConfigId == serverConfigId) {
        pendingCallbacks.add(onComplete)
        return
      }
      if (activeConfigId == null) {
        activeConfigId = serverConfigId
        pendingCallbacks.add(onComplete)
      } else {
        // Different server config while another is running: execute independently and complete its own caller.
        pushSessions { pushOk, pushErr ->
          reconcileProgress { reconcileOk, reconcileErr ->
            onComplete(pushOk && reconcileOk, pushErr ?: reconcileErr)
          }
        }
        return
      }
    }

    val finishAll = { ok: Boolean, err: String? ->
      val callbacks: List<(Boolean, String?) -> Unit>
      synchronized(lock) {
        activeConfigId = null
        callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
      }
      callbacks.forEach { it(ok, err) }
    }

    pushSessions { pushOk, pushErr ->
      reconcileProgress { reconcileOk, reconcileErr ->
        finishAll(pushOk && reconcileOk, pushErr ?: reconcileErr)
      }
    }
  }
}
