package com.audiobookshelf.app.listening

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadRequestFactory
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.player.PlayerNotificationService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.paperdb.Paper
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All file mutations and Paper books are restricted to the established isolated cache root. */
@RunWith(AndroidJUnit4::class)
class ListeningStorageDeviceTest {
  private lateinit var context: Context
  private var fixtureGrant = true
  private val db = DbManager()
  private val books = listOf("device", "localLibraryItems", "localFolders", "localMediaProgress", "playbackSession", "log", "ownedAudio", "progressReceipts", "downloadHousekeeping", "cleanupFirstFinished", "keepDownloaded", "podcastDownloadRules", "automaticTransfers", "deferredTransfers", "downloadValidators", "downloadItem", "downloadItems")
  private val id = ListeningIdentity("fixture", "fixture-user", "book")
  private lateinit var audio: File
  private lateinit var ebook: File
  private lateinit var cover: File
  @Before fun setUp() {
    InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = root
      // Documents live in a separate disposable APK; model its URI grant only for that authority.
      override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int {
        if (uri.authority == "com.audiobookshelf.app.debug.test.listeningdocs") return if (fixtureGrant) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED
        return super.checkCallingOrSelfUriPermission(uri, modeFlags)
      }
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach { check(File(Paper.book(it).path).parentFile == root) { "Refusing to touch active database" }; Paper.book(it).destroy() }
    PlayerNotificationService.preparedListeningIdentity = null
    MediaProgressSyncer.activePlaybackSession = null
    File(root,"downloads").deleteRecursively()
    val folder = File(root,"downloads/book").apply { mkdirs() }
    audio = File(folder,"audio.mp3").apply { writeText("fixture-audio") }
    ebook = File(folder,"book.epub").apply { writeText("fixture-ebook") }
    cover = File(folder,"cover.jpg").apply { writeText("fixture-cover") }
    val local = localBook(audio)
    db.saveLocalLibraryItem(local)
    db.saveLocalMediaProgress(progress())
    db.saveLocalFolder(LocalFolder("internal-book", "Internal", "", "", "", "internal", "book"))
    ManagedDownloadStore.save(OwnedAudio(id, local.id, null, "audio", Uri.fromFile(audio).toString(), audio.path, local.folderId, audio.length()))
  }
  @After fun tearDown() {
    InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    PlayerNotificationService.preparedListeningIdentity = null
    MediaProgressSyncer.activePlaybackSession = null
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    books.forEach { Paper.book(it).destroy() }
    File(context.filesDir,"downloads").deleteRecursively()
  }
  private fun localBook(file:File): LocalLibraryItem {
    val metadata = jacksonObjectMapper().readValue("""{"title":"Fixture book","authors":[],"genres":[],"explicit":false}""",BookMetadata::class.java)
    val track = AudioTrack(1,0.0,100.0,"Audio",Uri.fromFile(file).toString(),"audio/mpeg",null,true,"audio",1)
    val book = Book(metadata,null,emptyList(),null,null, mutableListOf(track),null,0,100.0,1)
    return LocalLibraryItem("local_book","internal-book",file.parent!!,file.parent!!,"",false,"book",book,
      mutableListOf(LocalFile("audio",file.name,Uri.fromFile(file).toString(),file.parent!!,file.path,"audio/mpeg",file.length()),
        LocalFile("ebook",ebook.name,Uri.fromFile(ebook).toString(),ebook.parent!!,ebook.path,"application/epub+zip",ebook.length()),
        LocalFile("cover",cover.name,Uri.fromFile(cover).toString(),cover.parent!!,cover.path,"image/jpeg",cover.length())),
      Uri.fromFile(cover).toString(),cover.path,true,id.configId,"http://fixture.invalid",id.userId,id.itemId)
  }
  private fun progress(finished:Boolean=true) = LocalMediaProgress("local_book","local_book",null,100.0,if(finished)1.0 else 0.5,if(finished)0.0 else 50.0,finished,null,null,100,1,if(finished)1 else null,id.configId,"http://fixture.invalid",id.userId,id.itemId,null)
  @Test fun manualOfflineCleanupPreservesEbookCoverMetadataAndProgressAcrossHousekeeping() {
    val cleanup = FinishedDownloadCleanup(context)
    assertTrue(cleanup.preview().single().eligible)
    val result = cleanup.delete(listOf(id))
    assertTrue((result["failures"] as List<*>).isEmpty())
    assertFalse(audio.exists()); assertTrue(ebook.exists()); assertTrue(cover.exists())
    db.cleanLocalLibraryItems(context); db.cleanLocalMediaProgress()
    assertTrue(db.getLocalMediaProgress("local_book")!!.isFinished)
    assertNotNull(db.getLocalLibraryItem("local_book"))
    assertFalse(db.getLocalLibraryItem("local_book")!!.hasTracks(context,null))
    assertTrue(cleanup.preview().isEmpty())
  }
  @Test fun automaticCleanupWaitsForExactFinishedAcknowledgementAndGracePeriod() {
    ManagedDownloadStore.save(DownloadHousekeepingSettings(cleanBooks=true,bookDelayHours=0))
    val cleanup = FinishedDownloadCleanup(context)
    assertEquals("Waiting for confirmed progress sync",cleanup.preview(true).single().reason)
    val p = ProgressStatusStore.get(id)
    ProgressStatusStore.complete(id,p.revision,p.position,true,true)
    assertTrue(cleanup.preview(true).single().eligible)
    db.saveLocalMediaProgress(progress(false))
    cleanup.cleanupAutomatic()
    assertTrue(audio.exists())
  }
  @Test fun protectionAndPreparedPlayerBlockManualCleanup() {
    ManagedDownloadStore.protect(id,true)
    assertEquals("Keep downloaded",FinishedDownloadCleanup(context).preview().single().reason)
    ManagedDownloadStore.protect(id,false)
    PlayerNotificationService.preparedListeningIdentity=id
    assertEquals("In use by player",FinishedDownloadCleanup(context).preview().single().reason)
    FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue(audio.exists())
  }
  @Test fun changingProtectionAfterPreviewIsRecheckedBeforeDeletion() {
    assertTrue(FinishedDownloadCleanup(context).preview().single().eligible)
    ManagedDownloadStore.protect(id,true)
    val result=FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue((result["failures"] as List<*>).isNotEmpty()); assertTrue(audio.exists())
  }
  @Test fun malformedAndOutsidePathsCannotBecomeDeletionTargets() {
    val outside=File(context.filesDir,"outside-audio.mp3").apply { writeText("preserve") }
    val original=ManagedDownloadStore.audio().single()
    ManagedDownloadStore.save(original.copy(path=outside.path,contentUrl=Uri.fromFile(outside).toString(),bytes=outside.length()))
    assertFalse(FinishedDownloadCleanup(context).preview().single().eligible)
    FinishedDownloadCleanup(context).delete(listOf(id)); assertTrue(outside.exists()); assertTrue(audio.exists())
    outside.delete()
  }
  @Test fun interruptedDeletionJournalFinishesMetadataCleanupWithoutDeletingOtherFiles() {
    val original=ManagedDownloadStore.audio().single()
    ManagedDownloadStore.save(original.copy(deleting=true))
    assertTrue(audio.delete())
    val result=FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue((result["failures"] as List<*>).isEmpty())
    assertTrue(ebook.exists()); assertTrue(cover.exists())
    assertNotNull(ManagedDownloadStore.audio().single().removedAt)
    assertTrue(db.getLocalLibraryItem("local_book")!!.media.getAudioTracks().isEmpty())
  }
  private fun sharedAudio(name: String): android.net.Uri {
    val tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.audiobookshelf.app.debug.test.listeningdocs", "root")
    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root/$name")
    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write("fixture-audio".toByteArray()) }
    val local = db.getLocalLibraryItem("local_book")!!
    local.folderId = "fixture-shared"
    local.localFiles.find { it.id == "audio" }!!.apply { contentUrl = uri.toString(); absolutePath = "/fixture/$name" }
    (local.media as Book).tracks!!.single().contentUrl = uri.toString()
    db.saveLocalLibraryItem(local)
    db.saveLocalFolder(LocalFolder("fixture-shared", "Disposable test tree", tree.toString(), "", "/fixture", "external", "book"))
    ManagedDownloadStore.save(ManagedDownloadStore.audio().single().copy(folderId="fixture-shared",contentUrl=uri.toString(),path="/fixture/$name"))
    return uri
  }
  @Test fun sharedStorageDeletesOnlyRecordedAudioAndPreservesLocalProgress() {
    val uri=sharedAudio("audio.mp3")
    val document=androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)!!
    val tree=android.provider.DocumentsContract.buildTreeDocumentUri("com.audiobookshelf.app.debug.test.listeningdocs","root")
    assertTrue("fixture document writable",document.canWrite())
    assertTrue("fixture root writable",androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)!!.canWrite())
    assertTrue("fixture audio file",document.isFile)
    assertEquals(13L,document.length())
    assertTrue("fixture child relationship",android.provider.DocumentsContract.isChildDocument(context.contentResolver,android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root"),uri))
    assertTrue(FinishedDownloadCleanup(context).preview().single().toString(), FinishedDownloadCleanup(context).preview().single().eligible)
    val result=FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue((result["failures"] as List<*>).isEmpty())
    assertFalse(androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)!!.exists())
    assertTrue(audio.exists()); assertTrue(ebook.exists()); assertTrue(cover.exists())
    assertTrue(db.getLocalMediaProgress("local_book")!!.isFinished)
  }
  @Test fun sharedReadOnlyDocumentIsSkippedWithoutDiscardingMetadata() {
    val uri=sharedAudio("locked.mp3")
    assertFalse(FinishedDownloadCleanup(context).preview().single().eligible)
    FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue(androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)!!.exists())
    assertTrue(db.getLocalLibraryItem("local_book")!!.localFiles.any { it.id == "audio" })
  }
  @Test fun staleSessionSnapshotCannotAcknowledgeNewerLocalPosition() {
    val first=ProgressStatusStore.get(id)
    db.saveLocalMediaProgress(progress(false).apply { lastUpdate=200 })
    assertEquals(-1L,ProgressStatusStore.beginSnapshot(id,first.updatedAt,first.position,first.finished))
    ProgressStatusStore.complete(id,first.revision,first.position,true,true)
    assertEquals("waiting",ProgressStatusStore.snapshot(id)["state"])
    val restored=Paper.book("progressReceipts").read<ProgressReceipt>(id.key)!!
    assertTrue(restored.revision > restored.acknowledgedRevision)
  }
  @Test fun sharedRevokedGrantKeepsAudioAndProgress() {
    val uri=sharedAudio("revoked.mp3")
    fixtureGrant=false
    assertFalse(FinishedDownloadCleanup(context).preview().single().eligible)
    FinishedDownloadCleanup(context).delete(listOf(id))
    assertTrue(androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)!!.exists())
    assertNotNull(db.getLocalMediaProgress("local_book"))
  }
  @Test fun deferredQueueRestoreKeepsStagingAndWorkerCannotStartManualTransfers() {
    val staging=File(context.filesDir,"download-staging/fixture.part").apply { parentFile!!.mkdirs(); writeText("partial") }
    val local=db.getLocalLibraryItem("local_book")!!
    val folder=db.getLocalFolder("internal-book")!!
    val track=(local.media as Book).tracks!!.single()
    val part=DownloadItemPart.make("fixture-transfer","audio.mp3",100,staging,File(audio.parentFile,"new.mp3"),"", "/fixture",folder,null,track,null)
    val item=DownloadItem("fixture-transfer",id.itemId,null,null,id.configId,"http://fixture.invalid",id.userId,"book",audio.parent!!,folder,"Fixture","",local.media, mutableListOf(part))
    db.saveDownloadItem(item)
    Paper.book("deferredTransfers").write(ListeningStorageKeys.compact(part.id),true)
    val manager=com.audiobookshelf.app.managers.DownloadItemManager(FolderScanner(context),context,object:com.audiobookshelf.app.managers.DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(item:DownloadItem) {}
      override fun onDownloadItemPartUpdate(part:DownloadItemPart) {}
      override fun onDownloadItemComplete(event:com.getcapacitor.JSObject) {}
      override fun onQueueChanged(hasWork:Boolean) {}
    })
    try {
      manager.setExecutionOwner(false,false)
      manager.restoreQueue()
      assertEquals("partial",staging.readText())
      assertEquals(7L,manager.downloadItemQueue.single().downloadItemParts.single().bytesDownloaded)
      manager.setExecutionOwner(true,false)
      manager.resumeWork()
      assertTrue(manager.currentDownloadItemParts.isEmpty())
      assertEquals("partial",staging.readText())
    } finally { manager.destroy(); staging.delete() }
  }
  @Test fun redownloadRestoresRetainedProgressInsteadOfResettingIt() {
    val local=db.getLocalLibraryItem("local_book")!!
    val track=(local.media as Book).tracks!!.single()
    FinishedDownloadCleanup(context).delete(listOf(id))
    db.saveLocalMediaProgress(progress(false).apply { lastUpdate=200 })
    audio.writeText("fixture-redownload")
    val folder=db.getLocalFolder("internal-book")!!
    val part=DownloadItemPart.make("fixture-redownload",audio.name,audio.length(),File(context.filesDir,"download-staging/redo.part"),audio,"", "/fixture",folder,null,track,null).apply { completed=true; moved=true }
    val item=DownloadItem("fixture-redownload",id.itemId,null,null,id.configId,"http://fixture.invalid",id.userId,"book",audio.parent!!,folder,"Fixture","",local.media, mutableListOf(part))
    var scanned:FolderScanner.DownloadItemScanResult?=null
    FolderScanner(context).scanDownloadItem(item) { scanned=it }
    assertNotNull(scanned)
    assertTrue(scanned!!.localLibraryItem.hasTracks(context,null))
    assertEquals(50.0,db.getLocalMediaProgress("local_book")!!.currentTime,0.001)
    assertFalse(db.getLocalMediaProgress("local_book")!!.isFinished)
    assertTrue(ebook.exists()); assertTrue(cover.exists())
  }
  @Test fun longIdentitiesAndFileIdsUseBoundedKeysWithoutLosingLegacyReceipts() {
    val longId=ListeningIdentity("account/".repeat(100),"user/".repeat(50),"book", "episode")
    val receipt=ProgressReceipt(id).saved(1,20.0,false,2,"Fixture")
    Paper.book("progressReceipts").write(id.rawKey,receipt)
    Paper.book("progressReceipts").delete(id.key)
    assertEquals(receipt,ProgressStatusStore.get(id))
    assertFalse(Paper.book("progressReceipts").contains(id.rawKey))
    assertEquals(64,longId.key.length)
    val original=ManagedDownloadStore.audio().single()
    val longFile=original.copy(identity=longId,fileId="long audio filename/".repeat(100))
    ManagedDownloadStore.save(longFile)
    assertEquals(64,longFile.key.length)
    assertTrue(ManagedDownloadStore.audio().any { it.identity==longId })
  }
  @Test fun bridgeIdentityRoundTripExcludesDerivedKeys() {
    val mapper=jacksonObjectMapper()
    val encoded=mapper.writeValueAsString(id)
    assertFalse(encoded.contains("serverLinked"))
    assertFalse(encoded.contains("\"key\""))
    assertEquals(id,mapper.readValue(encoded,ListeningIdentity::class.java))
  }
  @Test fun failedLocalWriteCannotClaimSavedOrSynced() {
    val other=id.copy(itemId="write-failure")
    try { ProgressStatusStore.persist(other,1,42.0,false) { throw java.io.IOException("fixture") }; fail("Expected write failure") } catch (_:java.io.IOException) { }
    assertEquals(0L,ProgressStatusStore.get(other).savedAt)
    assertEquals("error",ProgressStatusStore.snapshot(other)["state"])
  }
}
