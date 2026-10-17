package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.audiobookshelf.app.server.ApiHandler
import org.junit.Assert.*
import org.junit.Test

class OfflineProgressSyncTest {
  private fun session(
    id: String,
    itemId: String = "book-1",
    episodeId: String? = null,
    updatedAt: Long = 1_000L,
    currentTime: Double = 30.0,
    timeListening: Long = 30L,
    playMethod: Int = PLAYMETHOD_LOCAL,
    configId: String = "server-1"
  ) = PlaybackSession(
    id = id,
    userId = "user-1",
    libraryItemId = itemId,
    episodeId = episodeId,
    mediaType = if (episodeId == null) "book" else "podcast",
    mediaMetadata = MediaTypeMetadata("Title", false),
    deviceInfo = DeviceInfo("dev", "Google", "Pixel", 34, "0.14.2"),
    chapters = emptyList(),
    displayTitle = "Title",
    displayAuthor = "Author",
    coverPath = null,
    duration = 100.0,
    playMethod = playMethod,
    startedAt = 500L,
    updatedAt = updatedAt,
    timeListening = timeListening,
    audioTracks = mutableListOf(AudioTrack(1, 0.0, 100.0, "track.mp3", "/track.mp3", "audio/mpeg", null, true, null, null)),
    currentTime = currentTime,
    libraryItem = null,
    localLibraryItem = null,
    localEpisodeId = null,
    serverConnectionConfigId = configId,
    serverAddress = "https://abs.example",
    mediaPlayer = "exo-player"
  )

  private fun localProgress(
    id: String = "local_book-1",
    itemId: String? = "book-1",
    episodeId: String? = null,
    currentTime: Double = 0.0,
    progress: Double = 0.0,
    isFinished: Boolean = false,
    ebookLocation: String? = null,
    ebookProgress: Double? = null,
    lastUpdate: Long = 1_000L,
    startedAt: Long = 500L,
    finishedAt: Long? = null,
    configId: String? = "server-1"
  ) = LocalMediaProgress(
    id = id,
    localLibraryItemId = "local_item",
    localEpisodeId = episodeId?.let { "local_$it" },
    duration = 100.0,
    progress = progress,
    currentTime = currentTime,
    isFinished = isFinished,
    ebookLocation = ebookLocation,
    ebookProgress = ebookProgress,
    lastUpdate = lastUpdate,
    startedAt = startedAt,
    finishedAt = finishedAt,
    serverConnectionConfigId = configId,
    serverAddress = "https://abs.example",
    serverUserId = "user-1",
    libraryItemId = itemId,
    episodeId = episodeId
  )

  private fun serverProgress(
    id: String = "mp-1",
    itemId: String = "book-1",
    episodeId: String? = null,
    currentTime: Double = 0.0,
    progress: Double = 0.0,
    isFinished: Boolean = false,
    ebookLocation: String? = null,
    ebookProgress: Double? = null,
    lastUpdate: Long = 1_000L
  ) = MediaProgress(
    id = id,
    libraryItemId = itemId,
    episodeId = episodeId,
    duration = 100.0,
    progress = progress,
    currentTime = currentTime,
    isFinished = isFinished,
    ebookLocation = ebookLocation,
    ebookProgress = ebookProgress,
    lastUpdate = lastUpdate,
    startedAt = 500L,
    finishedAt = if (isFinished) lastUpdate else null
  )

  @Test fun streamingFailingOrSkippedTicksDoNotQuadraticallyInflateSessionTimeListening() {
    val tracker = ListeningTimeTracker()
    val pbSession = session("stream-1", playMethod = PLAYMETHOD_DIRECTPLAY, timeListening = 0L)
    tracker.start(now = 1_000L, isSameSession = false)

    // Tick 1 at 16s: server sync fails
    val step1 = tracker.onTick(16_000L)!!
    pbSession.syncData(MediaProgressSyncData(step1.sessionDeltaSeconds, 100.0, 15.0))
    tracker.onServerSyncComplete(16_000L, success = false, syncedSeconds = step1.serverPayloadSeconds, generation = step1.generation)
    assertEquals(15L, pbSession.timeListening)
    assertEquals(15L, step1.serverPayloadSeconds)

    // Tick 2 at 31s: server sync fails again; session delta must be 15s (total 30s), while server payload carries 30s
    val step2 = tracker.onTick(31_000L)!!
    pbSession.syncData(MediaProgressSyncData(step2.sessionDeltaSeconds, 100.0, 30.0))
    tracker.onServerSyncComplete(31_000L, success = false, syncedSeconds = step2.serverPayloadSeconds, generation = step2.generation)
    assertEquals(15L, step2.sessionDeltaSeconds)
    assertEquals(30L, pbSession.timeListening)
    assertEquals(30L, step2.serverPayloadSeconds)

    // Tick 3 at 46s: server sync succeeds with accumulated 45s; pending server time resets to 0
    val step3 = tracker.onTick(46_000L)!!
    pbSession.syncData(MediaProgressSyncData(step3.sessionDeltaSeconds, 100.0, 45.0))
    assertEquals(45L, pbSession.timeListening)
    assertEquals(45L, step3.serverPayloadSeconds)
    tracker.onServerSyncComplete(46_000L, success = true, syncedSeconds = step3.serverPayloadSeconds, generation = step3.generation)

    // Tick 4 at 61s: fresh 15s for both session and server payload
    val step4 = tracker.onTick(61_000L)!!
    pbSession.syncData(MediaProgressSyncData(step4.sessionDeltaSeconds, 100.0, 60.0))
    assertEquals(60L, pbSession.timeListening)
    assertEquals(15L, step4.serverPayloadSeconds)
  }

  @Test fun meteredNetworkAllowsServerSyncEvery60SecondsEvenWhenLocalTicksRunEvery15Seconds() {
    val tracker = ListeningTimeTracker()
    tracker.start(now = 0L, isSameSession = false)

    for (tickMs in listOf(15_000L, 30_000L, 45_000L)) {
      assertFalse("Should not sync server at $tickMs on metered network", tracker.shouldSyncServer(tickMs, isUnmeteredNetwork = false))
      assertNotNull(tracker.onTick(tickMs))
    }
    assertTrue("Should sync server at 60s on metered network", tracker.shouldSyncServer(60_000L, isUnmeteredNetwork = false))
    val stepAt60 = tracker.onTick(60_000L)!!
    tracker.onServerSyncComplete(60_000L, success = true, syncedSeconds = stepAt60.serverPayloadSeconds, generation = stepAt60.generation)
    assertFalse(tracker.shouldSyncServer(75_000L, isUnmeteredNetwork = false))
  }

  @Test fun delayedPreviousSessionResponseCannotAcknowledgeCurrentListeningTime() {
    val tracker = ListeningTimeTracker()
    tracker.start(1_000L, isSameSession = false)
    val previous = tracker.onTick(16_000L)!!
    tracker.start(20_000L, isSameSession = false)
    val current = tracker.onTick(35_000L)!!

    tracker.onServerSyncComplete(36_000L, true, previous.serverPayloadSeconds, previous.generation)
    assertEquals(15L, tracker.pendingServerListeningTime)
    assertEquals(20_000L, tracker.lastServerSyncTime)
    assertTrue(tracker.shouldSyncServer(80_000L, isUnmeteredNetwork = false))
    assertEquals(30L, tracker.onTick(50_000L)!!.serverPayloadSeconds)

    // A valid acknowledgement still subtracts its snapshot, retaining later unsent ticks.
    tracker.onServerSyncComplete(51_000L, true, current.serverPayloadSeconds, current.generation)
    assertEquals(15L, tracker.pendingServerListeningTime)
  }

  @Test fun delayedResponseBeforePauseCannotAcknowledgeResumedSameSession() {
    val tracker = ListeningTimeTracker()
    tracker.start(1_000L, isSameSession = false)
    val beforePause = tracker.onTick(16_000L)!!
    tracker.onPauseOrReset()
    tracker.onServerSyncComplete(17_000L, true, beforePause.serverPayloadSeconds, beforePause.generation)
    assertEquals(0L, tracker.lastServerSyncTime)
    tracker.start(20_000L, isSameSession = true)
    val resumed = tracker.onTick(35_000L)!!
    tracker.onServerSyncComplete(36_000L, true, beforePause.serverPayloadSeconds, beforePause.generation)
    assertEquals(15L, tracker.pendingServerListeningTime)
    assertEquals(20_000L, tracker.lastServerSyncTime)
    tracker.onServerSyncComplete(37_000L, true, resumed.serverPayloadSeconds, resumed.generation)
    assertEquals(0L, tracker.pendingServerListeningTime)
  }

  @Test fun batchSyncRetainsFailedAndConcurrentlyUpdatedSessionsInPaperDb() {
    val s1Succeeded = session("s1", updatedAt = 1_000L)
    val s2Failed = session("s2", updatedAt = 1_000L)
    val s3UpdatedInFlight = session("s3", updatedAt = 1_000L)
    val s4ActiveNewer = session("s4", updatedAt = 1_000L)

    val results = listOf(
      ApiHandler.LocalSessionSyncResult("s1", success = true, progressSynced = true, error = null),
      ApiHandler.LocalSessionSyncResult("s2", success = false, progressSynced = false, error = "Item locked"),
      ApiHandler.LocalSessionSyncResult("s3", success = true, progressSynced = true, error = null),
      ApiHandler.LocalSessionSyncResult("s4", success = true, progressSynced = true, error = null)
    )
    val storedNow = mapOf(
      "s1" to session("s1", updatedAt = 1_000L),
      "s2" to session("s2", updatedAt = 1_000L),
      "s3" to session("s3", updatedAt = 2_000L), // newer tick saved while request was in flight
      "s4" to session("s4", updatedAt = 1_000L)
    )
    val activeNow = session("s4", updatedAt = 2_500L) // active session advanced in memory

    val removable = OfflineSessionCleanup.idsToRemoveAfterBatchSync(
      listOf(s1Succeeded, s2Failed, s3UpdatedInFlight, s4ActiveNewer),
      results,
      storedNow,
      activeNow
    )
    assertEquals(setOf("s1"), removable)
    assertFalse(OfflineSessionCleanup.shouldRemoveAfterSingleSync("s3", 1_000L, storedNow["s3"], null))
    assertFalse(OfflineSessionCleanup.shouldRemoveAfterSingleSync("s4", 1_000L, storedNow["s4"], activeNow))
    assertTrue(OfflineSessionCleanup.shouldRemoveAfterSingleSync("s1", 1_000L, storedNow["s1"], null))
  }

  @Test fun reconcilerPushesOfflineAudioFinishedToggleAndNewItemsWhilePullingNewerServerProgress() {
    // 1. Offline audio marked finished (server has older unfinished progress)
    val finishedOffline = localProgress(
      id = "local_b1", itemId = "book-1", currentTime = 0.0, progress = 1.0,
      isFinished = true, lastUpdate = 5_000L, finishedAt = 5_000L
    )
    val olderServerB1 = serverProgress(id = "mp-1", itemId = "book-1", currentTime = 40.0, progress = 0.4, isFinished = false, lastUpdate = 2_000L)

    // 2. Brand-new downloaded podcast episode marked finished or read offline (no server progress record yet)
    val newOfflineEpisode = localProgress(
      id = "local_p1-ep1", itemId = "pod-1", episodeId = "ep-1", currentTime = 0.0, progress = 1.0,
      isFinished = true, lastUpdate = 6_000L, finishedAt = 6_000L
    )

    // 3. Brand-new downloaded ebook read offline (no server progress record yet)
    val newOfflineEbook = localProgress(
      id = "local_eb1", itemId = "ebook-1", ebookLocation = "epubcfi(/6/4)", ebookProgress = 0.5,
      lastUpdate = 7_000L
    )

    // 4. Server has newer progress than local, and no pending unsynced session exists
    val staleLocal = localProgress(
      id = "local_b2", itemId = "book-2", currentTime = 10.0, progress = 0.1,
      isFinished = false, lastUpdate = 1_000L
    )
    val newerServerB2 = serverProgress(id = "mp-2", itemId = "book-2", currentTime = 80.0, progress = 0.8, isFinished = false, lastUpdate = 9_000L)

    // 5. Server has newer timestamp than LocalMediaProgress, but a still-unsynced PlaybackSession in Paper DB is even newer
    val protectedByPendingSession = localProgress(
      id = "local_b3", itemId = "book-3", currentTime = 90.0, progress = 0.9,
      isFinished = false, lastUpdate = 8_000L
    )
    val serverB3 = serverProgress(id = "mp-3", itemId = "book-3", currentTime = 50.0, progress = 0.5, isFinished = false, lastUpdate = 8_500L)
    val pendingSessionB3 = session("s-b3", itemId = "book-3", updatedAt = 9_500L, currentTime = 90.0)

    val plan = LocalProgressReconciler.reconcile(
      localItems = listOf(finishedOffline, newOfflineEpisode, newOfflineEbook, staleLocal, protectedByPendingSession),
      serverItems = listOf(olderServerB1, newerServerB2, serverB3),
      unsyncedSessions = listOf(pendingSessionB3)
    )

    // Only book-2 should be overwritten from server
    assertEquals(listOf("local_b2"), plan.updatedLocalItems.map { it.id })
    assertEquals(80.0, plan.updatedLocalItems.single().currentTime, 0.001)

    // Patches must be generated for book-1 (finished toggle), pod-1/ep-1 (new finished), and ebook-1 (new ebook)
    assertEquals(setOf("book-1", "pod-1", "ebook-1"), plan.serverPatches.map { it.libraryItemId }.toSet())
    val b1Patch = plan.serverPatches.first { it.libraryItemId == "book-1" }
    assertEquals(true, b1Patch.isFinished)
    assertEquals(1.0, b1Patch.progress!!, 0.001)
    assertEquals(5_000L, b1Patch.lastUpdate)

    val epPatch = plan.serverPatches.first { it.libraryItemId == "pod-1" }
    assertEquals("ep-1", epPatch.episodeId)
    assertEquals(true, epPatch.isFinished)

    val ebookPatch = plan.serverPatches.first { it.libraryItemId == "ebook-1" }
    assertEquals("epubcfi(/6/4)", ebookPatch.ebookLocation)
    assertEquals(0.5, ebookPatch.ebookProgress!!, 0.001)
  }

  @Test fun coordinatorPushesSessionsBeforeReconcilingAndCoalescesConcurrentHeadlessCalls() {
    val coordinator = OfflineSyncCoordinator()
    val order = mutableListOf<String>()
    var finishPush: ((Boolean, String?) -> Unit)? = null
    var finishReconcile: (() -> Unit)? = null
    val results = mutableListOf<Pair<Boolean, String?>>()

    coordinator.sync(
      serverConfigId = "server-1",
      pushSessions = { cb ->
        order.add("push")
        finishPush = cb
      },
      reconcileProgress = { cb ->
        order.add("reconcile")
        finishReconcile = cb
      },
      onComplete = { ok, err -> results.add(ok to err) }
    )

    // A second concurrent call (e.g. Android Auto or Widget while WebView sync is in flight) must coalesce
    coordinator.sync(
      serverConfigId = "server-1",
      pushSessions = { order.add("duplicate-push") },
      reconcileProgress = { order.add("duplicate-reconcile") },
      onComplete = { ok, err -> results.add(ok to err) }
    )

    // Phase 1 must be "push", not "reconcile"
    assertEquals(listOf("push"), order)
    finishPush!!.invoke(true, null)
    assertEquals(listOf("push", "reconcile"), order)
    finishReconcile!!.invoke()
    assertEquals(listOf(true to null, true to null), results)
  }

  @Test fun legacyPaperRecordsWithNullIdsAreSafelyIgnored() {
    val brokenSession = session("legacy")
    brokenSession.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(brokenSession, null)
    val goodSession = session("valid")
    @Suppress("UNCHECKED_CAST")
    val rawSessions = listOf(null, brokenSession, session(""), goodSession) as List<PlaybackSession>
    assertEquals(listOf("valid"), OfflineSessionCleanup.validSessions(rawSessions).map { it.id })

    val brokenProgress = localProgress("legacy")
    brokenProgress.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(brokenProgress, null)
    @Suppress("UNCHECKED_CAST")
    val rawProgress = listOf(null, brokenProgress, localProgress("")) as List<LocalMediaProgress>
    val plan = LocalProgressReconciler.reconcile(rawProgress, emptyList(), emptyList())
    assertTrue(plan.updatedLocalItems.isEmpty())
    assertTrue(plan.serverPatches.isEmpty())
  }
}
