package com.audiobookshelf.app.media

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.audiobookshelf.app.server.ApiHandler
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import io.paperdb.Book
import io.paperdb.Paper
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated cache directory, never the installed fork's persisted user data. */
@RunWith(AndroidJUnit4::class)
class OfflineProgressSyncDeviceTest {
  private lateinit var context: Context
  private val db = DbManager()
  private val books = listOf("playbackSession", "localMediaProgress", "localLibraryItems", "device", "log")

  @Before
  fun setUp() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    // Paper caches book paths process-wide: share the recovery suite's isolated cache root.
    val isolatedFiles = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = isolatedFiles
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach { name ->
      val book = Paper.book(name)
      check(File(book.path).parentFile == context.filesDir) { "Refusing to touch app user data" }
      book.destroy()
    }
  }

  @After
  fun tearDown() {
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    books.forEach { Paper.book(it).destroy() }
  }

  @Test
  fun legacyKryoRecordsWithMissingIdAreIgnoredWhileValidPaperRecordsPersist() {
    val validSession = session("valid-session", updatedAt = 1000L)
    val legacySession = session("legacy-session", updatedAt = 900L)
    db.savePlaybackSession(validSession)
    writeLegacyWithoutId("playbackSession", "legacy-session", legacySession, PlaybackSession::class.java)

    val validProgress = localProgress("valid-progress", "li-1", lastUpdate = 2000L, isFinished = true)
    val legacyProgress = localProgress("legacy-progress", "li-2", lastUpdate = 1500L, isFinished = false)
    db.saveLocalMediaProgress(validProgress)
    writeLegacyWithoutId("localMediaProgress", "legacy-progress", legacyProgress, LocalMediaProgress::class.java)

    assertEquals(listOf("valid-session"), db.getPlaybackSessions().map { it.id })
    assertNotNull(db.getPlaybackSession("valid-session"))
    assertNull(db.getPlaybackSession("legacy-session"))
    assertEquals(listOf("valid-progress"), db.getAllLocalMediaProgress().map { it.id })
  }

  @Test
  fun batchSessionSyncAndProgressReconciliationPreserveFailedAndActivePaperRecords() {
    val sOk = session("s-ok", itemId = "li-1", updatedAt = 1000L)
    val sFail = session("s-fail", itemId = "li-2", updatedAt = 4000L)
    val sActive = session("s-active", itemId = "li-3", updatedAt = 1000L)
    db.savePlaybackSession(sOk)
    db.savePlaybackSession(sFail)
    db.savePlaybackSession(sActive)

    val lmpOfflineFinished = localProgress("lli-1", "li-1", lastUpdate = 5000L, isFinished = true, progress = 1.0, currentTime = 0.0)
    val lmpProtectedByUnsyncedSession = localProgress("lli-2", "li-2", lastUpdate = 1000L, isFinished = false, progress = 0.2, currentTime = 200.0)
    val lmpServerNewer = localProgress("lli-4", "li-4", lastUpdate = 1000L, isFinished = false, progress = 0.2, currentTime = 200.0)
    db.saveLocalMediaProgress(lmpOfflineFinished)
    db.saveLocalMediaProgress(lmpProtectedByUnsyncedSession)
    db.saveLocalMediaProgress(lmpServerNewer)

    val events = mutableListOf<String>()
    var completedSuccess = false

    val coordinator = OfflineSyncCoordinator()
    val sentSnapshots = db.getPlaybackSessions().map {
      session(it.id, itemId = it.libraryItemId ?: "li-1", updatedAt = it.updatedAt)
    }

    coordinator.sync(
      serverConfigId = "cfg-1",
      pushSessions = { cb ->
        events.add("push:${sentSnapshots.map { it.id }.joinToString(",")}")
        // Simulate s-active receiving a new local tick in Paper DB while request is in flight
        val updatedActive = session("s-active", itemId = "li-3", updatedAt = 2500L)
        db.savePlaybackSession(updatedActive)

        val results = listOf(
          ApiHandler.LocalSessionSyncResult("s-ok", success = true, progressSynced = true, error = null),
          ApiHandler.LocalSessionSyncResult("s-fail", success = false, progressSynced = false, error = "Server error"),
          ApiHandler.LocalSessionSyncResult("s-active", success = true, progressSynced = true, error = null)
        )
        val persistedNow = sentSnapshots.mapNotNull { sent ->
          db.getPlaybackSession(sent.id)?.let { sent.id to it }
        }.toMap()
        val idsToRemove = OfflineSessionCleanup.idsToRemoveAfterBatchSync(
          sentSnapshots,
          results,
          persistedNow,
          updatedActive
        )
        idsToRemove.forEach { db.removePlaybackSession(it) }
        cb(true, null)
      },
      reconcileProgress = { cb ->
        events.add("reconcile")
        val serverProgressList = listOf(
          MediaProgress(
            id = "mp-1",
            libraryItemId = "li-1",
            episodeId = null,
            duration = 1000.0,
            progress = 0.4,
            currentTime = 400.0,
            isFinished = false,
            ebookLocation = null,
            ebookProgress = null,
            lastUpdate = 2000L,
            startedAt = 100L,
            finishedAt = null
          ),
          MediaProgress(
            id = "mp-2",
            libraryItemId = "li-2",
            episodeId = null,
            duration = 1000.0,
            progress = 0.5,
            currentTime = 500.0,
            isFinished = false,
            ebookLocation = null,
            ebookProgress = null,
            lastUpdate = 3000L,
            startedAt = 100L,
            finishedAt = null
          ),
          MediaProgress(
            id = "mp-4",
            libraryItemId = "li-4",
            episodeId = null,
            duration = 1000.0,
            progress = 0.8,
            currentTime = 800.0,
            isFinished = false,
            ebookLocation = null,
            ebookProgress = null,
            lastUpdate = 6000L,
            startedAt = 100L,
            finishedAt = null
          )
        )
        val plan = LocalProgressReconciler.reconcile(
          localItems = db.getAllLocalMediaProgress(),
          serverItems = serverProgressList,
          unsyncedSessions = db.getPlaybackSessions()
        )
        plan.updatedLocalItems.forEach { updated ->
          events.add("saveLocal:${updated.id}:${updated.currentTime}")
          db.saveLocalMediaProgress(updated)
        }
        plan.serverPatches.forEach { patch ->
          events.add("patchServer:${patch.libraryItemId}:isFinished=${patch.isFinished}")
        }
        cb(true, null)
      },
      onComplete = { success, _ ->
        events.add("done")
        completedSuccess = success
      }
    )

    assertTrue(completedSuccess)
    assertEquals(
      listOf(
        "push:s-ok,s-fail,s-active",
        "reconcile",
        "saveLocal:lli-4:800.0",
        "patchServer:li-1:isFinished=true",
        "done"
      ),
      events
    )
    assertNull(db.getPlaybackSession("s-ok"))
    assertNotNull(db.getPlaybackSession("s-fail"))
    assertEquals(2500L, db.getPlaybackSession("s-active")?.updatedAt)
    assertEquals(200.0, db.getLocalMediaProgress("lli-2")!!.currentTime, 0.001)
    assertEquals(800.0, db.getLocalMediaProgress("lli-4")!!.currentTime, 0.001)
  }

  private fun session(id: String, itemId: String = "li-1", updatedAt: Long): PlaybackSession {
    return PlaybackSession(
      id = id,
      userId = "u1",
      libraryItemId = itemId,
      episodeId = null,
      mediaType = "book",
      mediaMetadata = MediaTypeMetadata("Test", false),
      deviceInfo = DeviceInfo("dev1", "Google", "Pixel", 36, "0.14.2"),
      chapters = emptyList(),
      displayTitle = "Test",
      displayAuthor = "Author",
      coverPath = null,
      duration = 1000.0,
      playMethod = PLAYMETHOD_LOCAL,
      startedAt = 100L,
      updatedAt = updatedAt,
      timeListening = 30L,
      audioTracks = mutableListOf(AudioTrack(1, 0.0, 1000.0, "track.mp3", "/track.mp3", "audio/mpeg", null, true, null, null)),
      currentTime = 30.0,
      libraryItem = null,
      localLibraryItem = null,
      localEpisodeId = null,
      serverConnectionConfigId = "cfg-1",
      serverAddress = "https://abs.example.com",
      mediaPlayer = "exo-player"
    )
  }

  private fun localProgress(
    id: String,
    libraryItemId: String,
    lastUpdate: Long,
    isFinished: Boolean,
    progress: Double = if (isFinished) 1.0 else 0.5,
    currentTime: Double = 500.0
  ): LocalMediaProgress {
    return LocalMediaProgress(
      id = id,
      localLibraryItemId = id,
      localEpisodeId = null,
      duration = 1000.0,
      progress = progress,
      currentTime = currentTime,
      isFinished = isFinished,
      ebookLocation = null,
      ebookProgress = null,
      lastUpdate = lastUpdate,
      startedAt = 100L,
      finishedAt = if (isFinished) lastUpdate else null,
      serverConnectionConfigId = "cfg-1",
      serverAddress = "https://abs.example.com",
      serverUserId = "u1",
      libraryItemId = libraryItemId,
      episodeId = null
    )
  }

  private fun <T : Any> writeLegacyWithoutId(bookName: String, key: String, value: T, clazz: Class<T>) {
    val book = Paper.book(bookName)
    val storage = Book::class.java.getDeclaredField("mStorage").apply { isAccessible = true }.get(book)
    val kryo = storage.javaClass.getDeclaredMethod("getKryo").apply { isAccessible = true }
      .invoke(storage) as Kryo
    val legacySerializer = CompatibleFieldSerializer<T>(kryo, clazz).apply {
      removeField("id")
    }
    val registration = kryo.getRegistration(clazz)
    val originalSerializer = registration.serializer
    try {
      registration.serializer = legacySerializer
      book.write(key, value)
    } finally {
      registration.serializer = originalSerializer
    }
  }
}
