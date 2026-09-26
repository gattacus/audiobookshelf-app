package com.audiobookshelf.app.managers

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStorageFailureTest {
  private lateinit var context: Context
  private val db = DbManager()
  private val books = listOf("downloadItems", "device", "log")
  private val managers = mutableListOf<DownloadItemManager>()
  private val ids = mutableListOf<String>()

  @Before fun setUp() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    // Paper caches book paths process-wide: share the recovery suite's isolated cache root.
    val isolated = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = isolated
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach {
      check(File(Paper.book(it).path).parentFile == context.filesDir)
      Paper.book(it).destroy()
    }
  }

  @After fun tearDown() {
    // A failed isolation check must never let teardown destroy the active database.
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    managers.forEach { it.destroy() }
    ids.forEach { IncompleteDownloadCleanup.cancel(context, it) }
    books.forEach { Paper.book(it).destroy() }
    context.filesDir.deleteRecursively()
  }

  @Test fun insufficientSpaceStopsInternalAndSharedTransfersWithoutTouchingFinalFiles() {
    for (shared in listOf(false, true)) {
      val item = item(if (shared) "shared" else "internal", shared)
      val part = item.downloadItemParts.first()
      val finalFile = File(part.finalDestinationPath).apply { writeText("existing completed media") }
      val staging = File(part.destinationPath).apply { writeText("partial bytes") }
      val updates = mutableListOf<DownloadItemPart>()
      val manager = manager(updates)
      manager.addDownloadItem(item)
      manager.resumeWork()
      assertFalse(manager.hasWork())
      assertTrue(manager.currentDownloadItemParts.isEmpty())
      assertTrue(part.failed)
      assertFalse(part.waitingForSpace)
      assertNull(part.downloadId)
      assertEquals(0, part.retryCount)
      assertEquals("INSUFFICIENT_STORAGE", updates.last().failureCode)
      assertTrue(part.requiredStorageBytes > part.availableStorageBytes)
      assertEquals("partial bytes", staging.readText())
      assertEquals("existing completed media", finalFile.readText())
      val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(part))
      assertEquals("INSUFFICIENT_STORAGE", json["failureCode"].asText())
      assertTrue(json["requiredStorageBytes"].asLong() > json["availableStorageBytes"].asLong())
      assertFalse(json["waitingForSpace"].asBoolean())
      repeat(3) { manager.resumeWork() }
      assertEquals(1, updates.size)
    }
  }

  @Test fun failureSurvivesPaperRestoreUntilExplicitRetry() {
    val item = item("restart", false)
    val manager = manager()
    manager.addDownloadItem(item)
    manager.resumeWork()
    manager.destroy()
    val restored = manager()
    restored.restoreQueue()
    val part = restored.downloadItemQueue.single().downloadItemParts.single()
    assertFalse(restored.hasWork())
    assertTrue(part.failed)
    assertEquals("INSUFFICIENT_STORAGE", part.failureCode)
    assertTrue(part.requiredStorageBytes > part.availableStorageBytes)
    assertTrue(restored.retryDownloadItem(item.id))
    assertTrue(restored.hasWork())
    assertFalse(part.failed)
    assertNull(part.failureCode)
    assertEquals(0L, part.requiredStorageBytes)
    // Still insufficient: one new explicit attempt fails immediately, without automatic retries.
    restored.resumeWork()
    assertFalse(restored.hasWork())
    assertEquals("INSUFFICIENT_STORAGE", part.failureCode)
  }

  @Test fun runningTransferSignalsStorageFailureWithoutWritingOrGenericRetry() {
    val done = CountDownLatch(1)
    val events = mutableListOf<String>()
    val staging = File(context.filesDir, "stream.part")
    ServerSocket(0).use { server ->
      val serverThread = Thread {
        server.accept().use { socket ->
          val reader = socket.getInputStream().bufferedReader()
          while (!reader.readLine().isNullOrEmpty()) { }
          socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest".toByteArray())
          socket.getOutputStream().flush()
        }
      }.apply { start() }
      val callback = object : DownloadItemManager.InternalProgressCallback {
        override fun onSizeResolved(totalBytes: Long) = Unit
        override fun onProgress(totalBytesWritten: Long, progress: Long) { events.add("progress") }
        override fun onComplete(failed: Boolean) { events.add("complete"); done.countDown() }
        override fun onAuthError() { events.add("auth"); done.countDown() }
        override fun onInsufficientSpace() { events.add("storage"); done.countDown() }
      }
      val handle = InternalDownloadManager(staging, 4L, callback, { false })
              .download("http://127.0.0.1:${server.localPort}/audio", "test")
      try {
        assertTrue(done.await(10, TimeUnit.SECONDS))
        serverThread.join(1000)
        assertEquals(listOf("storage"), events)
        assertEquals(0L, staging.length())
      } finally { handle.cancel() }
    }
  }

  private fun manager(updates: MutableList<DownloadItemPart> = mutableListOf()): DownloadItemManager =
          DownloadItemManager(FolderScanner(context), context,
                  object : DownloadItemManager.DownloadEventEmitter {
                    override fun onDownloadItem(item: DownloadItem) = Unit
                    override fun onDownloadItemPartUpdate(part: DownloadItemPart) { updates.add(part.copy()) }
                    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
                    override fun onQueueChanged(hasWork: Boolean) = Unit
                  }, { DownloadStorageBudget.Space(4_997_087_232L, 117_332_594_688L) })
                  .also { managers.add(it) }

  private fun item(id: String, shared: Boolean): DownloadItem {
    ids.add(id)
    // A rejected subfolder avoids querying any real shared-storage provider in this fixture.
    val folder = LocalFolder(if (shared) "shared" else "internal-book", "Test", "content://test/tree/root",
            "", context.filesDir.path, if (shared) "external" else "internal", "book")
    val staging = File(context.filesDir, "$id.part")
    val finalFile = File(context.filesDir, "$id-final.mp3")
    val part = DownloadItemPart(id, id, "$id.mp3", 6_665_982L, staging.path, finalFile.path,
            "/api/items/$id/file/audio/download", folder.name, folder.contentUrl, folder.id,
            null, null, null, false, false, false, false, Uri.parse("https://example.invalid/audio"),
            Uri.fromFile(staging), Uri.fromFile(finalFile), null, "..", null, null, 0L, 0L)
    return DownloadItem(id, id, null, null, "test", "https://example.invalid", "test", "book",
            context.filesDir.path, folder, id, "test", MediaType(MediaTypeMetadata(id, false), null), mutableListOf(part))
  }
}
