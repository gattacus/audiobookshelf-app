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
class DownloadCancellationDeviceTest {
  private lateinit var context: Context
  private val books = listOf("downloadItems", "device", "log", "automaticTransfers", "deferredTransfers", "downloadValidators")
  private val managers = mutableListOf<DownloadItemManager>()
  private val db = DbManager()
  @Before fun setUp() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val isolated = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = isolated
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach { check(File(Paper.book(it).path).parentFile == context.filesDir); Paper.book(it).destroy() }
  }
  @After fun tearDown() {
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    managers.forEach { it.destroy() }
    books.forEach { Paper.book(it).destroy() }
    context.filesDir.deleteRecursively()
  }

  @Test fun cancelsOnlyOneQueuedEpisodePreservingFinalMediaAndSharedStaging() {
    for (shared in listOf(false, true)) {
      val manager = manager().apply { setExecutionOwner(false, false) }
      val first = item("first-$shared", shared)
      val second = item("second-$shared", shared)
      val part = first.downloadItemParts.single()
      val final = File(part.finalDestinationPath).apply { writeText("completed media") }
      val staging = File(part.destinationPath).apply { writeText("partial media") }
      val sharedStaging = File(context.filesDir, "download-staging/cover-$shared.part").apply { writeText("shared cover") }
      first.downloadItemParts.add(part.copy(id = "cover-$shared", destinationPath = sharedStaging.path))
      second.downloadItemParts.add(part.copy(id = "cover-$shared", downloadItemId = second.id, destinationPath = sharedStaging.path))
      manager.addDownloadItem(first); manager.addDownloadItem(second)
      assertEquals(DownloadItemManager.CancelResult.CANCELLED, manager.cancelDownloadItem(first.id))
      assertFalse(staging.exists())
      assertEquals("completed media", final.readText())
      assertEquals("shared cover", sharedStaging.readText())
      assertEquals(listOf(second.id), manager.downloadItemQueue.map { it.id })
      assertEquals(listOf(second.id), db.getDownloadItems().map { it.id })
      assertEquals(DownloadItemManager.CancelResult.NOT_FOUND, manager.cancelDownloadItem(first.id))
      manager.cancelDownloadItem(second.id)
    }
  }

  @Test fun refusesMissingLegacyFinalOrOutsideStagingPathsWithoutDeletingAnything() {
    for (invalid in listOf("", File(context.filesDir, "completed.mp3").path,
      File(context.filesDir.parentFile, "outside.part").path, File(context.filesDir, "download-staging/../private.part").path)) {
      val manager = manager().apply { setExecutionOwner(false, false) }
      val item = item("invalid-${managers.size}")
      val final = File(item.downloadItemParts.single().finalDestinationPath).apply { writeText("keep") }
      item.downloadItemParts[0] = item.downloadItemParts.single().copy(destinationPath = invalid)
      manager.addDownloadItem(item)
      assertEquals(DownloadItemManager.CancelResult.INVALID_STAGING, manager.cancelDownloadItem(item.id))
      assertEquals("keep", final.readText())
      assertEquals(1, manager.downloadItemQueue.size)
    }
  }

  @Test fun refusesCancellationDuringFileMoveOrCompletedItemScan() {
    val manager = manager().apply { setExecutionOwner(false, false) }
    val item = item("finishing")
    val part = item.downloadItemParts.single()
    val staging = File(part.destinationPath).apply { writeText("keep") }
    manager.addDownloadItem(item)
    part.isMoving = true
    assertEquals(DownloadItemManager.CancelResult.FINISHING, manager.cancelDownloadItem(item.id))
    part.isMoving = false; part.moved = true; part.completed = true
    assertEquals(DownloadItemManager.CancelResult.FINISHING, manager.cancelDownloadItem(item.id))
    assertEquals("keep", staging.readText())
  }

  @Test fun bridgeKeepsQueuedTransferVisibleDuringExecutionOwnerHandover() {
    val host = com.audiobookshelf.app.services.DownloadServiceHost::class.java
    val managerField = host.getDeclaredField("manager").apply { isAccessible = true }
    val emitterField = host.getDeclaredField("bridgeEmitter").apply { isAccessible = true }
    // This instrumentation process has no live Activity/service; use only our isolated manager.
    assertNull(managerField.get(null))
    val originalEmitter = emitterField.get(null)
    val events = mutableListOf<Boolean>()
    val isolatedManager = manager().apply { setExecutionOwner(false, false) }
    isolatedManager.addDownloadItem(item("handover"))
    val emitter = object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) = Unit
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) = Unit
      override fun onDownloadItemComplete(jsobj: JSObject) = Unit
      override fun onQueueChanged(hasWork: Boolean) { events.add(hasWork) }
    }
    try {
      managerField.set(null, isolatedManager); emitterField.set(null, emitter)
      val forwardingClass = host.declaredClasses.first { it.simpleName == "ForwardingEmitter" }
      val forwarding = forwardingClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) as DownloadItemManager.DownloadEventEmitter
      forwarding.onQueueChanged(false)
      assertEquals(listOf(true), events)
      assertEquals(DownloadItemManager.CancelResult.CANCELLED, isolatedManager.cancelDownloadItem("handover"))
      forwarding.onQueueChanged(false)
      assertEquals(false, events.last())
    } finally { managerField.set(null, null); emitterField.set(null, originalEmitter) }
  }

  @Test fun stopsActiveHttpAndDoesNotRestoreCancelledQueueOrRecreatePartialFile() {
    ServerSocket(0).use { server ->
      val release = CountDownLatch(1)
      val sent = CountDownLatch(1)
      val thread = Thread {
        try {
          server.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) { }
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nETag: \"test\"\r\nConnection: close\r\n\r\npartial".toByteArray())
            socket.getOutputStream().flush(); sent.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { socket.getOutputStream().write(ByteArray(65536)); socket.getOutputStream().flush() }
          }
        } catch (_: Exception) { sent.countDown() }
      }.apply { start() }
      val manager = manager()
      val item = item("running").copy(serverAddress = "http://127.0.0.1:${server.localPort}")
      val part = item.downloadItemParts.single()
      manager.addDownloadItem(item); manager.resumeWork()
      try {
        assertTrue(sent.await(10, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (part.bytesDownloaded == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(part.bytesDownloaded > 0L)
        assertEquals(DownloadItemManager.CancelResult.CANCELLED, manager.cancelDownloadItem(item.id))
        release.countDown(); thread.join(2000)
        assertFalse(thread.isAlive)
        Thread.sleep(150)
        assertFalse(File(part.destinationPath).exists())
        assertTrue(manager.currentDownloadItemParts.isEmpty())
        assertTrue(db.getDownloadItems().isEmpty())
        assertFalse(manager.hasWork())
        val restored = manager(); restored.restoreQueue()
        assertTrue(restored.downloadItemQueue.isEmpty())
      } finally { release.countDown(); manager.destroy() }
    }
  }

  @Test fun cancelledResponseCannotOpenStagingAfterSizeCallback() {
    val staging = File(context.filesDir, "download-staging/late.part").apply { parentFile!!.mkdirs() }
    val ready = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    lateinit var handle: InternalDownloadManager.DownloadHandle
    ServerSocket(0).use { server ->
      val thread = Thread {
        runCatching { server.accept().use { socket ->
          val reader = socket.getInputStream().bufferedReader()
          while (!reader.readLine().isNullOrEmpty()) { }
          socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest".toByteArray())
          socket.getOutputStream().flush()
        } }
      }.apply { start() }
      handle = InternalDownloadManager(staging, 4, object : DownloadItemManager.InternalProgressCallback {
        override fun onSizeResolved(totalBytes: Long) { ready.await(5, TimeUnit.SECONDS); handle.cancel(); staging.delete(); cancelled.countDown() }
        override fun onProgress(totalBytesWritten: Long, progress: Long) = Unit
        override fun onComplete(failed: Boolean) = Unit
        override fun onAuthError() = Unit
        override fun onInsufficientSpace() = Unit
      }, { true }).download("http://127.0.0.1:${server.localPort}/late", "test")
      ready.countDown()
      try {
        assertTrue(cancelled.await(5, TimeUnit.SECONDS)); thread.join(2000)
        Thread.sleep(150)
        assertFalse(staging.exists())
      } finally { handle.cancel() }
    }
  }

  private fun manager() = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) = Unit
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) = Unit
    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
    override fun onQueueChanged(hasWork: Boolean) = Unit
  }, { DownloadStorageBudget.Space(100_000_000_000, 200_000_000_000) }).also { managers.add(it) }

  private fun item(id: String, shared: Boolean = false): DownloadItem {
    val staging = File(context.filesDir, "download-staging/$id.part").apply { parentFile!!.mkdirs() }
    val final = File(context.filesDir, "$id.mp3")
    val folder = LocalFolder(if (shared) "shared" else "internal-book", "Test", "content://test/tree/root", "", context.filesDir.path,
      if (shared) "external" else "internal", "book")
    val part = DownloadItemPart(id, id, "$id.mp3", 1048576, staging.path, final.path, "/test", folder.name, folder.contentUrl, folder.id,
      null, null, null, false, false, false, false, Uri.parse("http://127.0.0.1/test"), Uri.fromFile(staging), Uri.fromFile(final), null,
      "..", null, null, 0, 0)
    return DownloadItem(id, id, id, null, "test", "https://example.invalid", "test", "book", context.filesDir.path, folder,
      id, "test", MediaType(MediaTypeMetadata(id, false), null), mutableListOf(part))
  }
}
