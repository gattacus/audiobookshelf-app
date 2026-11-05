package com.audiobookshelf.app.listening

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.models.DownloadItem
import java.io.File

/** Deletes exact verified audio files, never item folders. Metadata and progress outlive media. */
class FinishedDownloadCleanup(private val context: Context, private val previewSettings: DownloadHousekeepingSettings? = null) {
  private val db get() = DeviceManager.dbManager
  data class Candidate(val identity: ListeningIdentity, val title: String, val bytes: Long,
    val eligible: Boolean, val reason: String?, val keepDownloaded: Boolean, val synced: Boolean)

  fun recordDownload(download: DownloadItem, local: LocalLibraryItem) {
    val transfer = ManagedDownloadStore.transfer(download)
    val episode = (local.media as? Podcast)?.episodes?.find { it.serverEpisodeId == download.episodeId }
    val identity = ListeningIdentity(download.serverConnectionConfigId, download.serverUserId, download.libraryItemId, download.episodeId)
    download.downloadItemParts.filter { it.audioTrack != null && !it.reusedExistingFile && it.moved }.forEach { part ->
      local.localFiles.find { it.absolutePath == part.finalDestinationPath || it.contentUrl == part.completedDestinationUri }?.let { file ->
        ManagedDownloadStore.save(OwnedAudio(identity, local.id, episode?.id, file.id, file.contentUrl, file.absolutePath, local.folderId, file.size, transfer != null))
      }
    }
  }
  /** Legacy internal downloads have deterministic app-owned paths and server-linked track records.
   * Shared files without a completed-download manifest cannot establish ownership retroactively. */
  fun backfill() {
    val known = ManagedDownloadStore.audio().map { it.key }.toSet()
    db.getLocalLibraryItems().forEach { item ->
      if (!item.folderId.startsWith("internal-") || item.libraryItemId.isNullOrBlank() || item.serverConnectionConfigId.isNullOrBlank() || item.serverUserId.isNullOrBlank()) return@forEach
      item.media.getAudioTracks().forEach { track ->
        val file = item.localFiles.find { it.id == track.localFileId } ?: return@forEach
        val ep = (item.media as? Podcast)?.episodes?.find { it.audioTrack?.localFileId == file.id }
        if (item.isPodcast && ep?.serverEpisodeId.isNullOrBlank()) return@forEach
        val audio = OwnedAudio(ListeningIdentity(item.serverConnectionConfigId!!, item.serverUserId!!, item.libraryItemId!!, ep?.serverEpisodeId), item.id, ep?.id, file.id, file.contentUrl, file.absolutePath, item.folderId, file.size)
        if (audio.key !in known && verified(audio)) ManagedDownloadStore.save(audio)
      }
    }
  }
  private fun verified(audio: OwnedAudio): Boolean = runCatching {
    val local = db.getLocalLibraryItem(audio.localItemId) ?: return false
    if (local.libraryItemId != audio.identity.itemId || local.serverConnectionConfigId != audio.identity.configId || local.serverUserId != audio.identity.userId || local.folderId != audio.folderId) return false
    if (audio.path.isBlank() || audio.contentUrl.isBlank()) return false
    val otherReferences = db.getLocalLibraryItems().any { it.id != local.id && it.localFiles.any { file -> file.contentUrl == audio.contentUrl || file.absolutePath == audio.path } }
    if (otherReferences) return false
    if (local.isPodcast && (local.media as Podcast).episodes.orEmpty().any { it.id != audio.localEpisodeId && it.audioTrack?.localFileId == audio.fileId }) return false
    if (audio.folderId.startsWith("internal-")) {
      val root = File(context.filesDir, "downloads").canonicalFile
      val file = File(audio.path)
      !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(root.toPath()) &&
        file.canonicalFile != root && Uri.parse(audio.contentUrl).scheme == "file" && File(Uri.parse(audio.contentUrl).path.orEmpty()).canonicalFile == file.canonicalFile &&
        (!file.exists() || (file.isFile && file.length() == audio.bytes))
    } else {
      val folder = db.getLocalFolder(audio.folderId) ?: return false
      val tree = Uri.parse(folder.contentUrl)
      val uri = Uri.parse(audio.contentUrl)
      if (tree.scheme != "content" || uri.scheme != "content" || tree.authority != uri.authority) return false
      val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
      val document = DocumentFile.fromSingleUri(context, uri) ?: return false
      val treeDocument = DocumentFile.fromTreeUri(context, tree) ?: return false
      if (!treeDocument.canWrite()) return false
      if (audio.deleting && !document.exists()) {
        // A journaled delete may have succeeded before the process persisted availability.
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        DocumentsContract.getDocumentId(uri).startsWith("$rootId/")
      } else DocumentsContract.isChildDocument(context.contentResolver, parent, uri) && document.canWrite() && document.isFile && document.length() == audio.bytes
    }
  }.getOrDefault(false)

  private fun progress(id: ListeningIdentity): LocalMediaProgress? = db.getAllLocalMediaProgress().filter { ListeningIdentity.from(it) == id }.maxByOrNull { it.lastUpdate }
  private fun reason(id: ListeningIdentity, audio: List<OwnedAudio>, automatic: Boolean): String? {
    val p = progress(id) ?: return "No saved progress"
    val settings = previewSettings ?: ManagedDownloadStore.settings()
    val receipt = ProgressStatusStore.get(id)
    val finishedAt = ManagedDownloadStore.firstFinished(id, p.isFinished, p.finishedAt)
    val active = MediaProgressSyncer.activePlaybackSession?.let { ListeningIdentity.from(it) == id } == true ||
      com.audiobookshelf.app.player.PlayerNotificationService.preparedListeningIdentity == id
    val pending = ProgressStatusStore.pending(id)
    val transferring = db.getDownloadItems().any { it.serverConnectionConfigId == id.configId && it.serverUserId == id.userId && it.libraryItemId == id.itemId && it.episodeId.orEmpty() == id.episodeId.orEmpty() }
    return FinishedCleanupPolicy.reason(p.isFinished, ManagedDownloadStore.protected(id), active, transferring,
      audio.isNotEmpty() && audio.all { verified(it) }, automatic,
      receipt.savedAt > 0 && receipt.finished && receipt.serverFinished == true && receipt.state(pending) == "synced" && receipt.updatedAt == p.lastUpdate,
      pending, finishedAt, System.currentTimeMillis(), if (id.episodeId == null) settings.bookDelayHours else settings.episodeDelayHours)
  }
  fun preview(automatic: Boolean = false): List<Candidate> {
    backfill()
    val audio = ManagedDownloadStore.audio().filter { it.removedAt == null }.groupBy { it.identity }
    val progress = db.getAllLocalMediaProgress().filter { it.isFinished }
    return progress.distinctBy { ListeningIdentity.from(it) }.mapNotNull { p ->
      val id = ListeningIdentity.from(p)
      val local = db.getLocalLibraryItem(p.localLibraryItemId) ?: return@mapNotNull null
      val files = audio[id].orEmpty()
      val episode = (local.media as? Podcast)?.episodes?.find { it.id == p.localEpisodeId }
      if (files.isEmpty() && (if (episode == null) local.media.getAudioTracks().isEmpty() else episode.audioTrack == null)) return@mapNotNull null
      val reason = reason(id, files, automatic)
      Candidate(id, episode?.title ?: local.title, files.sumOf { it.bytes }, reason == null, reason,
        ManagedDownloadStore.protected(id), ProgressStatusStore.get(id).state(ProgressStatusStore.pending(id)) == "synced")
    }
  }
  fun delete(ids: List<ListeningIdentity>, automatic: Boolean = false): Map<String, Any> = synchronized(ListeningFileAccess.lock) {
    var removed = 0L
    val failures = mutableListOf<String>()
    ids.distinct().forEach { id ->
      val files = ManagedDownloadStore.audio().filter { it.identity == id && it.removedAt == null }
      val blocked = reason(id, files, automatic)
      if (blocked != null) { failures.add("${id.itemId}: $blocked"); return@forEach }
      for (audio in files) {
        // Recheck after each file: playback/progress/protection may have changed since the preview.
        val recheck = reason(id, ManagedDownloadStore.audio().filter { it.identity == id && it.removedAt == null }, automatic)
        if (recheck != null) { failures.add(recheck); break }
        ManagedDownloadStore.save(audio.copy(deleting = true))
        try {
          val success = if (audio.folderId.startsWith("internal-")) File(audio.path).let { !it.exists() || it.delete() }
            else DocumentFile.fromSingleUri(context, Uri.parse(audio.contentUrl))?.let { (audio.deleting && !it.exists()) || it.delete() } == true
          if (!success) { failures.add("Could not remove an audio file"); continue }
          val local = db.getLocalLibraryItem(audio.localItemId) ?: continue
          local.localFiles.removeAll { it.id == audio.fileId }
          if (local.isPodcast) (local.media as Podcast).episodes?.find { it.id == audio.localEpisodeId }?.let { it.audioTrack = null; it.audioRemoved = true }
          else (local.media as Book).tracks?.removeAll { it.localFileId == audio.fileId }
          db.saveLocalLibraryItem(local)
          ManagedDownloadStore.save(audio.copy(deleting = false, removedAt = System.currentTimeMillis()))
          removed += audio.bytes
        } catch (e: Exception) { failures.add("Audio cleanup failed (${e.javaClass.simpleName})") }
      }
    }
    ProgressStatusStore.availabilityChanged()
    mapOf("removedBytes" to removed, "failures" to failures)
  }
  fun cleanupAutomatic() {
    val settings = ManagedDownloadStore.settings()
    val ids = preview(true).filter { it.eligible && (if (it.identity.episodeId == null) settings.cleanBooks else settings.cleanEpisodes) }.map { it.identity }
    if (ids.isNotEmpty()) delete(ids, true)
  }
}
