package com.audiobookshelf.app.services

import android.content.Context
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.managers.IncompleteDownloadCleanup
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLogger
import com.getcapacitor.JSObject
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared process owner used by the foreground service and the Capacitor bridge. */
object DownloadServiceHost {
  enum class ExistingDownloadResult { NOT_FOUND, ACTIVE, RETRIED, SERVICE_START_FAILED }

  data class NotificationStrings(
          val preparing: String,
          val downloadingFile: String,
          val waitingForStorage: String,
          val downloads: String,
          val cancel: String,
          val storageFailure: String
  )

  private var manager: DownloadItemManager? = null
  private var bridgeEmitter: DownloadItemManager.DownloadEventEmitter = NoopEmitter
  @Volatile private var service: DownloadService? = null
  @Volatile private var bridgeReady = false
  private val deferredCompletions = Collections.synchronizedList(mutableListOf<JSObject>())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var restoreJob: Job? = null
  private var backgroundOwners = 0

  @Synchronized
  fun ensure(context: Context): DownloadItemManager {
    if (manager == null) {
      val appContext = context.applicationContext
      DbManager.initialize(appContext)
      manager = DownloadItemManager(FolderScanner(appContext), appContext, ForwardingEmitter)
      manager!!.setExecutionOwner(service != null || backgroundOwners > 0, service != null)
      val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
      connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
          scope.launch { manager?.pauseDisallowed(); manager?.resumeWork(); com.audiobookshelf.app.listening.DownloadAutomation.schedule(appContext) }
        }
        override fun onLost(network: android.net.Network) { scope.launch { manager?.pauseDisallowed() } }
      })
      restoreJob = scope.launch {
        try {
          if (DeviceManager.dbManager.ensureValidDownloadQueue()) {
            IncompleteDownloadCleanup.cleanupExpired(appContext)
            manager!!.restoreQueue()
          } else {
            AbsLogger.error(TAG, "Skipping invalid download queue because it could not be cleared")
          }
          onRestoreComplete(appContext)
        } catch (restoreError: Exception) {
          AbsLogger.error(
                  TAG,
                  "Could not restore download queue (${restoreError.javaClass.simpleName}: ${restoreError.message})"
          )
        }
      }
    }
    return manager!!
  }

  /** Attaches the frontend after restored queue items have been emitted. */
  @Synchronized
  fun attachBridge(context: Context, emitter: DownloadItemManager.DownloadEventEmitter) {
    bridgeReady = false
    bridgeEmitter = emitter
    ensure(context).setEventEmitter(ForwardingEmitter)
    bridgeReady = true
    val completions = synchronized(deferredCompletions) {
      deferredCompletions.toList().also { deferredCompletions.clear() }
    }
    completions.forEach(bridgeEmitter::onDownloadItemComplete)
  }

  @Synchronized
  fun detachBridge() {
    bridgeReady = false
    bridgeEmitter = NoopEmitter
  }

  fun enqueue(context: Context, item: DownloadItem, callback: (String?) -> Unit) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.addDownloadItem(item)
      if (startService(context)) callback(null)
      else callback("Unable to start the Android download service")
    }
  }

  fun retryExisting(
          context: Context,
          downloadItemId: String,
          callback: (ExistingDownloadResult) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      val existing = queue.downloadItemQueue.find { it.id == downloadItemId }
      if (existing == null) {
        callback(ExistingDownloadResult.NOT_FOUND)
      } else if (!queue.retryDownloadItem(downloadItemId)) {
        callback(ExistingDownloadResult.ACTIVE)
      } else {
        DownloadService.dismissFailure(context, downloadItemId)
        callback(if (startService(context)) ExistingDownloadResult.RETRIED
                else ExistingDownloadResult.SERVICE_START_FAILED)
      }
    }
  }

  fun cancelDownloadItem(context: Context, id: String, callback: (DownloadItemManager.CancelResult) -> Unit) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      callback(queue.cancelDownloadItem(id))
    }
  }

  fun cancelAll(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.cancelAll()
    }
  }

  suspend fun acquireBackground(context: Context) {
    val queue = ensure(context)
    restoreJob?.join()
    synchronized(this) { backgroundOwners++; queue.setExecutionOwner(true, service != null) }
  }
  @Synchronized fun releaseBackground(context: Context) {
    backgroundOwners = (backgroundOwners - 1).coerceAtLeast(0)
    manager?.setExecutionOwner(service != null || backgroundOwners > 0, service != null)
  }
  fun hasAutomaticWork(context: Context): Boolean = ensure(context).downloadItemQueue.any { item ->
    com.audiobookshelf.app.listening.ManagedDownloadStore.transfer(item) != null &&
      com.audiobookshelf.app.listening.DownloadAutomation.allowed(context, item) &&
      item.downloadItemParts.any { !it.moved && !it.failed }
  }

  fun setNotificationStrings(
          context: Context,
          preparing: String,
          downloadingFile: String,
          waitingForStorage: String,
          downloads: String,
          cancel: String,
          storageFailure: String
  ) {
    context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREPARING, preparing)
            .putString(KEY_DOWNLOADING_FILE, downloadingFile)
            .putString(KEY_WAITING_FOR_STORAGE, waitingForStorage)
            .putString(KEY_DOWNLOADS, downloads)
            .putString(KEY_CANCEL, cancel)
            .putString(KEY_STORAGE_FAILURE, storageFailure)
            .apply()
  }

  fun notificationStrings(context: Context): NotificationStrings {
    val preferences = context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    return NotificationStrings(
            preferences.getString(KEY_PREPARING, DEFAULT_PREPARING) ?: DEFAULT_PREPARING,
            preferences.getString(KEY_DOWNLOADING_FILE, DEFAULT_DOWNLOADING_FILE)
                    ?: DEFAULT_DOWNLOADING_FILE,
            preferences.getString(KEY_WAITING_FOR_STORAGE, DEFAULT_WAITING_FOR_STORAGE)
                    ?: DEFAULT_WAITING_FOR_STORAGE,
            preferences.getString(KEY_DOWNLOADS, DEFAULT_DOWNLOADS) ?: DEFAULT_DOWNLOADS,
            preferences.getString(KEY_CANCEL, DEFAULT_CANCEL) ?: DEFAULT_CANCEL,
            preferences.getString(KEY_STORAGE_FAILURE, DEFAULT_STORAGE_FAILURE) ?: DEFAULT_STORAGE_FAILURE)
  }

  fun attachService(downloadService: DownloadService) {
    synchronized(this) { service = downloadService; manager?.setExecutionOwner(true, true) }
    startWork(downloadService)
  }

  @Synchronized
  fun detachService(downloadService: DownloadService) {
    if (service === downloadService) { service = null; manager?.setExecutionOwner(backgroundOwners > 0, false) }
  }

  fun startWork(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.resumeWork()
    }
  }

  private fun onRestoreComplete(context: Context) {
    val attachedService = synchronized(this) { service }
    if (attachedService != null) {
      manager?.resumeWork()
    } else if (bridgeReady && manager?.hasEligibleWork() == true) {
      startService(context)
    }
  }

  private fun startService(context: Context): Boolean {
    return try {
      ContextCompat.startForegroundService(context, DownloadService.intent(context))
      true
    } catch (e: RuntimeException) {
      AbsLogger.error(TAG, "Could not start download foreground service: ${e.message}")
      false
    }
  }

  private object ForwardingEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) { bridgeEmitter.onDownloadItem(downloadItem) }
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) {
      if (bridgeReady) bridgeEmitter.onDownloadItemPartUpdate(downloadItemPart)
      service?.onPartUpdate(downloadItemPart)
    }
    override fun onDownloadItemComplete(jsobj: JSObject) {
      if (bridgeReady) bridgeEmitter.onDownloadItemComplete(jsobj) else deferredCompletions.add(jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      // A service may have no eligible work while its execution owner is attaching
      // or the network policy pauses it. Keep queued transfers visible/cancellable.
      bridgeEmitter.onQueueChanged(manager?.hasWork() ?: hasWork)
      service?.onQueueChanged(hasWork)
    }
  }

  private object NoopEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) = Unit
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) = Unit
    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
    override fun onQueueChanged(hasWork: Boolean) = Unit
  }

  private const val NOTIFICATION_PREFERENCES = "download_notifications"
  private const val KEY_PREPARING = "preparing"
  private const val KEY_DOWNLOADING_FILE = "downloading_file"
  private const val KEY_WAITING_FOR_STORAGE = "waiting_for_storage"
  private const val KEY_DOWNLOADS = "downloads"
  private const val KEY_CANCEL = "cancel"
  private const val KEY_STORAGE_FAILURE = "storage_failure"
  private const val DEFAULT_PREPARING = "Preparing downloads"
  private const val DEFAULT_DOWNLOADING_FILE = "Downloading {0}"
  private const val DEFAULT_WAITING_FOR_STORAGE = "Waiting for available storage"
  private const val DEFAULT_DOWNLOADS = "Downloads"
  private const val DEFAULT_CANCEL = "Cancel"
  private const val DEFAULT_STORAGE_FAILURE = "Download stopped: not enough free space. Free {0} and retry."
  private const val TAG = "DownloadServiceHost"
}
