package com.audiobookshelf.app.plugins

import android.os.Environment
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.services.DownloadServiceHost
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler
  lateinit var downloadItemManager: DownloadItemManager

  private val clientEventEmitter = (object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem:DownloadItem) {
      notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(downloadItem)))
    }
    override fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart) {
      notifyListeners("onDownloadItemPartUpdate", JSObject(jacksonMapper.writeValueAsString(downloadItemPart)))
    }
    override fun onDownloadItemComplete(jsobj:JSObject) {
      notifyListeners("onItemDownloadComplete", jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      notifyListeners("onQueueChanged", JSObject().put("hasWork", hasWork))
    }
  })

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager = DownloadServiceHost.ensure(mainActivity)
    DownloadServiceHost.attachBridge(mainActivity, clientEventEmitter)
  }

  override fun handleOnDestroy() {
    DownloadServiceHost.detachBridge()
    super.handleOnDestroy()
  }

  @PluginMethod
  fun setDownloadNotificationStrings(call: PluginCall) {
    DownloadServiceHost.setNotificationStrings(
            mainActivity,
            call.getString("preparing") ?: "Preparing downloads",
            call.getString("downloadingFile") ?: "Downloading {0}",
            call.getString("waitingForStorage") ?: "Waiting for available storage",
            call.getString("downloads") ?: "Downloads",
            call.getString("cancel") ?: "Cancel",
            call.getString("storageFailure") ?: "Download stopped: not enough free space. Free {0} and retry.")
    call.resolve()
  }

  /** Replays restored queue items when the frontend subscribes to download events. */
  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  override fun addListener(call: PluginCall) {
    super.addListener(call)
    if (call.getString("eventName") == "onDownloadItem" && ::downloadItemManager.isInitialized) {
      downloadItemManager.downloadItemQueue.forEach { item ->
        notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(item)))
      }
    }
  }

  @PluginMethod
  fun cancelDownloadItem(call: PluginCall) {
    val id = call.getString("downloadItemId")?.takeIf { it.isNotBlank() }
            ?: return call.reject("Missing download item ID")
    DownloadServiceHost.cancelDownloadItem(mainActivity, id) { result ->
      call.resolve(JSObject().put("status", result.name))
    }
  }

  @PluginMethod
  fun retryDownloadItem(call: PluginCall) {
    val id = call.getString("downloadItemId") ?: return call.reject("Missing download item ID")
    DeviceManager.dbManager.getDownloadItems().find { it.id == id }?.let { com.audiobookshelf.app.listening.ManagedDownloadStore.finishTransfer(it) }
    DownloadServiceHost.retryExisting(mainActivity, id) { result ->
      when (result) {
        DownloadServiceHost.ExistingDownloadResult.RETRIED -> call.resolve()
        DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> call.reject("Download no longer exists")
        DownloadServiceHost.ExistingDownloadResult.ACTIVE -> call.reject("Download is already active")
        DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED ->
                call.reject("Unable to start the Android download service")
      }
    }
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    var localFolderId = call.data.getString("localFolderId", "").toString()
    AbsLogger.info(tag, "Requested download for item $libraryItemId${if (episodeId.isEmpty()) "" else " / episode $episodeId"}")

    val config = DeviceManager.serverConnectionConfig?.copy() ?: return call.reject("Select a server before downloading")
    val downloadId = DeviceManager.dbManager.getDownloadItems().find {
      it.serverConnectionConfigId == config.id && it.serverUserId == config.userId && it.libraryItemId == libraryItemId && it.episodeId.orEmpty() == episodeId
    }?.id ?: com.audiobookshelf.app.managers.DownloadRequestFactory.requestId(config, libraryItemId, episodeId)
    // An explicit user download/retry takes over an automatic transfer's scheduling policy.
    DeviceManager.dbManager.getDownloadItems().find { it.id == downloadId }?.let { com.audiobookshelf.app.listening.ManagedDownloadStore.finishTransfer(it) }
    DownloadServiceHost.retryExisting(mainActivity, downloadId) { result ->
      when (result) {
        DownloadServiceHost.ExistingDownloadResult.RETRIED -> call.resolve()
        DownloadServiceHost.ExistingDownloadResult.ACTIVE -> {
          AbsLogger.info(tag, "Download already active for $downloadId")
          call.resolve(JSObject("{\"error\":\"Download already started for this media entity\"}"))
        }
        DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED ->
                call.resolve(JSObject("{\"error\":\"Unable to start the Android download service\"}"))
        DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> {
          apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId, config) { libraryItem ->
            if (DeviceManager.serverConnectionConfig?.let { it.id == config.id && it.userId == config.userId } != true) return@getLibraryItemWithProgress call.reject("Server account changed")
            if (libraryItem == null) {
              call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
            } else {
              AbsLogger.info(tag, "Preparing download for ${libraryItem.id}")

              if (localFolderId == "") localFolderId = "internal-${libraryItem.mediaType}"
              var localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)
              if (localFolder == null && localFolderId.startsWith("internal-")) {
                AbsLogger.info(tag, "Creating internal download folder $localFolderId")
                localFolder = LocalFolder(localFolderId, "Internal App Storage", "", "", "", "internal", libraryItem.mediaType)
                DeviceManager.dbManager.saveLocalFolder(localFolder)
              }

              if (localFolder == null) {
                call.resolve(JSObject("{\"error\":\"Local Folder Not Found\"}"))
              } else if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
                call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
              } else if (episodeId.isNotEmpty()) {
                val podcast = libraryItem.media as Podcast
                val episode = podcast.episodes?.find { it.id == episodeId }
                if (episode == null) {
                  call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
                } else {
                  requestFactory(config).startLibraryItemDownload(libraryItem, localFolder, episode) { error -> resolveDownloadCall(call, error) }
                }
              } else {
                requestFactory(config).startLibraryItemDownload(libraryItem, localFolder, null) { error -> resolveDownloadCall(call, error) }
              }
            }
          }
        }
      }
    }
  }

  private fun requestFactory(config: ServerConnectionConfig) = com.audiobookshelf.app.managers.DownloadRequestFactory(context, config) { item, callback -> DownloadServiceHost.enqueue(context, item, callback) }

  private fun resolveDownloadCall(call: PluginCall, error: String?) {
    if (error == null) call.resolve()
    else call.resolve(JSObject().put("error", error))
  }

}
