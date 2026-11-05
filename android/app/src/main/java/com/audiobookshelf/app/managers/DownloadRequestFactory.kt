package com.audiobookshelf.app.managers

import android.content.Context
import android.os.Environment
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.*
import com.audiobookshelf.app.plugins.AbsLogger
import java.io.File

/** Shared request preparation; independent of Activity/WebView and bound to one account. */
class DownloadRequestFactory(
  private val context: Context,
  private val config: ServerConnectionConfig,
  private val enqueue: (DownloadItem, (String?) -> Unit) -> Unit
) {
  private val tag = "DownloadRequestFactory"
  companion object {
    fun requestId(config: ServerConnectionConfig, item: String, episode: String?) = "managed_" + com.audiobookshelf.app.device.DeviceManager.getBase64Id(com.audiobookshelf.app.listening.ListeningIdentity(config.id, config.userId, item, episode?.takeIf { it.isNotBlank() }).key)
  }
  // Item filenames could be the same if they are in sub-folders, this will make them unique
  private fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    cleanedRelPath = cleanStringForFileSystem(cleanedRelPath)
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Replace characters that cant be used in the file system
  // Reserved characters: ?:\"*|/\\<>
  private fun cleanStringForFileSystem(str:String):String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str
    newTitle = newTitle.replace(":", " -") // Special case replace : with -

    reservedCharacters.forEach {
      newTitle = newTitle.replace(it, "")
    }
    return newTitle
  }

  fun startLibraryItemDownload(
          libraryItem: LibraryItem,
          localFolder: LocalFolder,
          episode: PodcastEpisode?,
          callback: (String?) -> Unit
  ) {
    val isInternal = localFolder.id.startsWith("internal-")

    val owner = com.audiobookshelf.app.listening.ListeningStorageKeys.compact("${config.id}:${config.userId}")
    val existing = com.audiobookshelf.app.device.DeviceManager.dbManager.getLocalLibraryItems().find {
      it.libraryItemId == libraryItem.id && it.serverConnectionConfigId == config.id && it.serverUserId == config.userId && it.folderId == localFolder.id
    }
    val finalInternalFolderPath = existing?.absolutePath?.takeIf { path ->
      isInternal && runCatching { File(path).canonicalFile.toPath().startsWith(File(context.filesDir, "downloads").canonicalFile.toPath()) }.getOrDefault(false)
    } ?: "${context.filesDir}/downloads/$owner/${libraryItem.id}"
    val tempFolderPath =
            if (isInternal) {
              "${context.filesDir}/download-staging/$owner/${libraryItem.id}"
            } else {
              "${context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir}/download-staging/$owner/${libraryItem.id}"
            }


    if (libraryItem.mediaType == "book") {
      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
      val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())

      val tracks = libraryItem.media.getAudioTracks()
      AbsLogger.info(tag, "Queueing library item download with ${tracks.size} files")
      val itemSubfolder = "$bookAuthor/$bookTitle"
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItem = DownloadItem(requestId(config, libraryItem.id, null), libraryItem.id, null, libraryItem.userMediaProgress,config.id, config.address, config.userId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf())

      val book = libraryItem.media as Book
      book.ebookFile?.let { ebookFile ->
        val fileSize = ebookFile.metadata?.size ?: 0
        val serverPath = "/api/items/${libraryItem.id}/file/${ebookFile.ino}/download"
        val destinationFilename = getFilenameFromRelPath(ebookFile.metadata?.relPath ?: "")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,ebookFile,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      // Create download item part for each audio track
      val audioFiles = (libraryItem.media as Book).audioFiles ?: mutableListOf()
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0

        // TODO: Currently file ino is only stored on AudioFile. This should be updated server side to be in FileMetadata or on the AudioTrack
        val audioFileIno = audioFiles.find { it.metadata.path == audioTrack.metadata?.path }?.ino

        val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover-${libraryItem.id}.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename.part")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        enqueue(downloadItem, callback)
      } else callback("No downloadable files found")
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val audioFileIno = episode?.audioFile?.ino
      val fileSize = audioTrack?.metadata?.size ?: 0

      AbsLogger.info(tag, "Queueing podcast episode download")
      val itemFolderPath = if (isInternal) finalInternalFolderPath else "${localFolder.absolutePath}/$podcastTitle"
      val downloadItemId = requestId(config, libraryItem.id, episode?.id)
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, config.id, config.address, config.userId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, podcastTitle, libraryItem.media, mutableListOf())

      var serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")

      var destinationFile = File("$tempFolderPath/$destinationFilename.part")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename.part")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

        enqueue(downloadItem, callback)
    }
  }
}
