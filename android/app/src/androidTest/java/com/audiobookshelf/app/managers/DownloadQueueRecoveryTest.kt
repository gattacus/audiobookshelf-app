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
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import com.getcapacitor.JSObject
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
class DownloadQueueRecoveryTest {
  private lateinit var context: Context
  private val db = DbManager()
  private val books = listOf("downloadItems", "incompatibleDownloadItems", "localLibraryItems",
          "localMediaProgress", "device", "log")

  @Before fun setUp() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
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
    File(context.filesDir, "recovery-test").mkdirs()
  }

  @After fun tearDown() {
    // A failed isolation check must never let teardown destroy the active database.
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    books.forEach { Paper.book(it).destroy() }
    File(context.filesDir, "recovery-test").deleteRecursively()
  }

  @Test fun oldSchemaIsQuarantinedAndValidQueueSurvivesRepeatedReads() {
    val legacy = item("legacy")
    val completedFile = File(legacy.downloadItemParts.first().finalDestinationPath)
    completedFile.writeText("completed media")
    Paper.book("localLibraryItems").write("sentinel", "library unchanged")
    Paper.book("localMediaProgress").write("sentinel", "progress unchanged")
    Paper.book("device").write("sentinel", "credentials unchanged")
    writeOldSchema(legacy)
    // Verify the actual Paper schema-evolution behavior, not a constructor-created null.
    val read = Paper.book("downloadItems").read<DownloadItem>(legacy.id)!!
    val path: String? = read.downloadItemParts.first().destinationPath
    assertNull(path)
    assertNull(read.terminalFailureAt)
    assertEquals(0, read.downloadItemParts.first().retryCount)
    db.saveDownloadItem(item("valid"))

    assertEquals(listOf("valid"), db.getDownloadItems().map { it.id })
    assertFalse(Paper.book("downloadItems").contains(legacy.id))
    val quarantine = Paper.book("incompatibleDownloadItems")
    assertEquals(1, quarantine.allKeys.size)
    assertEquals("legacy", quarantine.read<DownloadItem>(quarantine.allKeys.first())!!.id)
    // Read again with a new manager, like the next startup. No repeated migration or data loss.
    assertEquals(listOf("valid"), DbManager().getDownloadItems().map { it.id })
    assertEquals(1, quarantine.allKeys.size)
    assertEquals("completed media", completedFile.readText())
    assertEquals("library unchanged", Paper.book("localLibraryItems").read<String>("sentinel"))
    assertEquals("progress unchanged", Paper.book("localMediaProgress").read<String>("sentinel"))
    assertEquals("credentials unchanged", Paper.book("device").read<String>("sentinel"))
  }

  @Test fun missingAndBlankPathsNeverReachExpiredCleanup() {
    for ((id, path) in listOf("internal" to null, "shared" to "  ")) {
      val item = item(id, shared = id == "shared")
      setMissingPath(item.downloadItemParts.first(), path)
      item.terminalFailureAt = System.currentTimeMillis() - 48L * 60 * 60 * 1000
      item.downloadItemParts.first().failed = true
      File(item.downloadItemParts.first().finalDestinationPath).writeText("preserve $id")
      db.saveDownloadItem(item)
    }
    IncompleteDownloadCleanup.cleanupExpired(context)
    assertTrue(db.getDownloadItems().isEmpty())
    assertEquals(2, Paper.book("incompatibleDownloadItems").allKeys.size)
    for (id in listOf("internal", "shared")) {
      assertEquals("preserve $id", File(context.filesDir, "recovery-test/$id-final.mp3").readText())
    }
  }

  @Test fun restoreRetryAndCancelOperateOnlyOnCompatibleParts() {
    val legacy = item("legacy")
    writeOldSchema(legacy)
    val legacyFinal = File(legacy.downloadItemParts.first().finalDestinationPath)
    legacyFinal.writeText("legacy completed media")
    val valid = item("valid")
    val staging = File(valid.downloadItemParts.first().destinationPath)
    staging.writeText("partial")
    val finalFile = File(valid.downloadItemParts.first().finalDestinationPath)
    finalFile.writeText("final media")
    db.saveDownloadItem(valid)
    val manager = manager()
    try {
      manager.restoreQueue()
      assertEquals(listOf("valid"), manager.downloadItemQueue.map { it.id })
      assertFalse(staging.exists())
      assertTrue(manager.retryDownloadItem("valid"))
      assertFalse(manager.retryDownloadItem("legacy"))
      staging.writeText("retry partial")
      manager.cancelAll()
      assertFalse(staging.exists())
      assertEquals("final media", finalFile.readText())
      assertEquals("legacy completed media", legacyFinal.readText())
      assertEquals(1, Paper.book("incompatibleDownloadItems").allKeys.size)
    } finally { manager.destroy() }
  }

  @Test fun completedPartInMixedLegacyItemIsPreservedInQuarantine() {
    val item = item("mixed")
    val completed = item.downloadItemParts.first().copy(id = "completed", completed = true, moved = true)
    item.downloadItemParts.add(completed)
    val finalFile = File(completed.finalDestinationPath)
    finalFile.writeText("completed")
    writeOldSchema(item)
    val manager = manager()
    try {
      manager.restoreQueue()
      assertTrue(manager.downloadItemQueue.isEmpty())
      assertFalse(manager.hasWork())
      assertEquals("completed", finalFile.readText())
      val book = Paper.book("incompatibleDownloadItems")
      val saved = book.read<DownloadItem>(book.allKeys.single())!!
      assertTrue(saved.downloadItemParts.last().moved)
      assertTrue(saved.downloadItemParts.last().completed)
    } finally { manager.destroy() }
  }

  @Test fun quarantineStorageFailureKeepsOriginalAndRetriesLater() {
    val item = item("legacy")
    writeOldSchema(item)
    val quarantinePath = File(Paper.book("incompatibleDownloadItems").path)
    quarantinePath.writeText("block directory creation")
    assertTrue(db.getDownloadItems().isEmpty())
    assertTrue(Paper.book("downloadItems").contains(item.id))
    assertTrue(quarantinePath.delete())
    // Reset the book's cached directory state, as a new process would after storage repair.
    Paper.book("incompatibleDownloadItems").destroy()
    assertTrue(db.getDownloadItems().isEmpty())
    assertFalse(Paper.book("downloadItems").contains(item.id))
    assertEquals(1, Paper.book("incompatibleDownloadItems").allKeys.size)
  }

  private fun item(id: String, shared: Boolean = false): DownloadItem {
    val folder = LocalFolder(if (shared) "shared" else "internal-book", "Test", "content://test/tree/root",
            "", context.filesDir.path, if (shared) "external" else "internal", "book")
    val staging = File(context.filesDir, "recovery-test/$id.part")
    val finalFile = File(context.filesDir, "recovery-test/$id-final.mp3")
    val part = DownloadItemPart(id, id, "$id.mp3", 100L, staging.path, finalFile.path,
            "/api/items/$id/file/audio/download", folder.name, folder.contentUrl, folder.id,
            null, null, null, false, false, false, false,
            Uri.parse("https://example.invalid/audio"), Uri.fromFile(staging), Uri.fromFile(finalFile),
            null, "test", null, null, 0L, 0L)
    return DownloadItem(id, id, null, null, "test", "https://example.invalid", "test", "book",
            context.filesDir.path, folder, id, "test", MediaType(MediaTypeMetadata(id, false), null),
            mutableListOf(part))
  }

  private fun manager() = DownloadItemManager(FolderScanner(context), context,
          object : DownloadItemManager.DownloadEventEmitter {
            override fun onDownloadItem(item: DownloadItem) = Unit
            override fun onDownloadItemPartUpdate(part: DownloadItemPart) = Unit
            override fun onDownloadItemComplete(jsobj: JSObject) = Unit
            override fun onQueueChanged(hasWork: Boolean) = Unit
          })

  private fun setMissingPath(part: DownloadItemPart, path: String?) {
    DownloadItemPart::class.java.getDeclaredField("destinationPath").apply {
      isAccessible = true
      set(part, path)
    }
  }

  /** Writes a Paper fixture with the pre-rewrite field schema, then restores today's reader. */
  private fun writeOldSchema(item: DownloadItem) {
    val book = Paper.book("downloadItems")
    val storage = Book::class.java.getDeclaredField("mStorage").apply { isAccessible = true }.get(book)
    val kryo = storage.javaClass.getDeclaredMethod("getKryo").apply { isAccessible = true }
            .invoke(storage) as Kryo
    val partClass = DownloadItemPart::class.java
    val itemClass = DownloadItem::class.java
    val oldPart = CompatibleFieldSerializer<DownloadItemPart>(kryo, partClass).apply {
      listOf("destinationPath", "completedDestinationUri", "lastUpdateTime", "retryCount",
              "authRetryCount", "waitingForSpace", "reusedExistingFile").forEach { removeField(it) }
    }
    val oldItem = CompatibleFieldSerializer<DownloadItem>(kryo, itemClass).apply {
      removeField("terminalFailureAt")
      removeField("stagingCleanupAt")
    }
    val partRegistration = kryo.getRegistration(partClass)
    val itemRegistration = kryo.getRegistration(itemClass)
    val currentPart = partRegistration.serializer
    val currentItem = itemRegistration.serializer
    try {
      partRegistration.serializer = oldPart
      itemRegistration.serializer = oldItem
      book.write(item.id, item)
    } finally {
      partRegistration.serializer = currentPart
      itemRegistration.serializer = currentItem
    }
  }
}
