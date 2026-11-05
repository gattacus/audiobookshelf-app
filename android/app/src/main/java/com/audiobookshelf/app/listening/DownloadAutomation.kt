package com.audiobookshelf.app.listening

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.player.PodcastPlaybackPolicy
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.services.DownloadServiceHost
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object DownloadAutomation {
  fun selected(): ServerConnectionConfig? = DeviceManager.dbManager.getDeviceData().getLastServerConnectionConfig()?.copy()
  fun networkAllowed(context: Context, wifiOnly: Boolean): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
  }
  fun allowed(context: Context, item: DownloadItem): Boolean {
    val transfer = ManagedDownloadStore.transfer(item) ?: return true
    val selected = selected() ?: return false
    val locallyFinished = DeviceManager.dbManager.getAllLocalMediaProgress().any { ListeningIdentity.from(it) == transfer.identity && it.isFinished }
    if (locallyFinished) return false
    val limit = ManagedDownloadStore.settings().automaticLimitBytes
    if (limit > 0) {
      val used = ManagedDownloadStore.audio().filter { it.automatic && it.removedAt == null }.sumOf { it.bytes } +
        DeviceManager.dbManager.getDownloadItems().sumOf { ManagedDownloadStore.transfer(it)?.reservedBytes ?: 0 }
      if (used > limit) return false
    }
    return selected.id == transfer.identity.configId && selected.userId == transfer.identity.userId &&
      ManagedDownloadStore.rule(transfer.identity.copy(episodeId = null)).let { it.enabled && networkAllowed(context, it.wifiOnly) }
  }
  fun schedule(context: Context, immediate: Boolean = true) {
    if (context.applicationContext !is android.app.Application) return
    val settings = ManagedDownloadStore.settings()
    val enabled = ManagedDownloadStore.rules().any { it.enabled } || settings.cleanBooks || settings.cleanEpisodes
    val work = WorkManager.getInstance(context)
    if (!enabled) {
      work.cancelUniqueWork("listening-housekeeping")
      work.cancelUniqueWork("listening-housekeeping-now")
      return
    }
    work.enqueueUniquePeriodicWork("listening-housekeeping", ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<ListeningHousekeepingWorker>(1, TimeUnit.HOURS).build())
    if (immediate) work.enqueueUniqueWork("listening-housekeeping-now", ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<ListeningHousekeepingWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
  }
  fun rememberAnchor(session: PlaybackSession) {
    val identity = ListeningIdentity.from(session)
    if (session.mediaType != "podcast" || !identity.serverLinked) return
    val id = identity.copy(episodeId = null)
    val rule = ManagedDownloadStore.rule(id)
    if (rule.anchor != identity.episodeId) ManagedDownloadStore.save(rule.copy(anchor = identity.episodeId, title = session.mediaMetadata.title))
  }
  suspend fun replenish(context: Context) {
    val config = selected() ?: return
    val api = ApiHandler(context)
    val rules = ManagedDownloadStore.rules().filter { it.enabled && it.identity.configId == config.id && it.identity.userId == config.userId }
    if (rules.isEmpty()) return
    for (rule in rules) {
      fun status(message: String) { ManagedDownloadStore.save(rule.copy(status = message, checkedAt = System.currentTimeMillis())) }
      if (!networkAllowed(context, rule.wifiOnly)) { status("Waiting for ${if (rule.wifiOnly) "Wi-Fi" else "network"}"); continue }
      val item = suspendCancellableCoroutine<LibraryItem?> { continuation ->
        api.getLibraryItemWithProgress(rule.identity.itemId, null, config) { if (continuation.isActive) continuation.resume(it) }
      }
      if (selected()?.let { it.id == config.id && it.userId == config.userId } != true) return replenish(context)
      val podcast = item?.media as? Podcast
      if (item == null || podcast == null) { status("Could not load podcast; will retry"); continue }
      val user = suspendCancellableCoroutine<com.audiobookshelf.app.models.User?> { continuation -> api.getCurrentUser(config) { if (continuation.isActive) continuation.resume(it) } }
      if (user == null) { status("Could not verify progress; will retry"); continue }
      val latestRule = ManagedDownloadStore.rule(rule.identity)
      if (latestRule.enabled != rule.enabled || latestRule.count != rule.count || latestRule.folderId != rule.folderId || latestRule.wifiOnly != rule.wifiOnly || latestRule.anchor != rule.anchor) return replenish(context)
      val local = DeviceManager.dbManager.getLocalLibraryItems("podcast").find {
        it.libraryItemId == item.id && it.serverConnectionConfigId == config.id && it.serverUserId == config.userId
      }
      val localEpisodes = (local?.media as? Podcast)?.episodes.orEmpty()
      val candidates = PodcastPlaybackPolicy.validEpisodes(podcast.episodes).map { ep ->
        val remote = user.mediaProgress.find { it.libraryItemId == item.id && it.episodeId == ep.id }
        val localProgress = DeviceManager.dbManager.getAllLocalMediaProgress().find {
          it.serverConnectionConfigId == config.id && it.serverUserId == config.userId && it.libraryItemId == item.id && it.episodeId == ep.id
        }
        val finished = if (localProgress != null && localProgress.lastUpdate >= (remote?.lastUpdate ?: 0)) localProgress.isFinished else remote?.isFinished == true
        PodcastPlaybackPolicy.Episode(ep.id, ep.publishedAt, ep.index, finished, ep.audioFile != null && ep.audioTrack != null)
      }
      val targets = NextDownloadPolicy.targets(candidates, rule.anchor, (podcast.metadata as? PodcastMetadata)?.type == "serial", rule.count)
      var message = "Up to date"
      for (episodeId in targets) {
        currentCoroutineContext().ensureActive()
        if (!networkAllowed(context, rule.wifiOnly) || selected()?.id != config.id) break
        val available = localEpisodes.find { it.serverEpisodeId == episodeId }
        if (available != null && local != null && runCatching { local.hasTracks(context, available) }.getOrDefault(false)) continue
        val queue = DownloadServiceHost.ensure(context)
        if (queue.downloadItemQueue.any { it.libraryItemId == item.id && it.episodeId == episodeId && it.serverConnectionConfigId == config.id && it.serverUserId == config.userId }) continue
        val episode = podcast.episodes?.find { it.id == episodeId } ?: continue
        val bytes = episode.audioTrack?.metadata?.size?.takeIf { it > 0 } ?: 100L * 1024 * 1024
        val limit = ManagedDownloadStore.settings().automaticLimitBytes
        val used = ManagedDownloadStore.audio().filter { it.automatic && it.removedAt == null }.sumOf { it.bytes } +
          queue.downloadItemQueue.sumOf { ManagedDownloadStore.transfer(it)?.reservedBytes ?: 0 }
        if (!AutomaticStorageBudget.canReserve(limit, used, 0, bytes)) { message = "Automatic storage limit reached"; break }
        val folder = DeviceManager.dbManager.getLocalFolder(rule.folderId) ?: if (rule.folderId == "internal-podcast") {
          LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast").also { DeviceManager.dbManager.saveLocalFolder(it) }
        } else null
        if (folder == null || (!folder.id.startsWith("internal-") && !runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(folder.contentUrl))?.canWrite() == true }.getOrDefault(false))) {
          message = "Download folder unavailable or not writable"; break
        }
        val error = suspendCancellableCoroutine<String?> { continuation ->
          DownloadRequestFactory(context, config) { download, callback ->
            ManagedDownloadStore.transfer(download, AutomaticTransfer(rule.identity.copy(episodeId = episodeId), rule.wifiOnly, bytes))
            queue.addDownloadItem(download)
            callback(null)
          }.startLibraryItemDownload(item, folder, episode) { if (continuation.isActive) continuation.resume(it) }
        }
        message = error ?: "Downloading next episodes"
      }
      // Do not overwrite a preference changed while requests were in flight.
      val current = ManagedDownloadStore.rule(rule.identity)
      ManagedDownloadStore.save(current.copy(status = message, checkedAt = System.currentTimeMillis()))
    }
  }
}

class ListeningHousekeepingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  companion object { private val workerLock = kotlinx.coroutines.sync.Mutex() }
  override suspend fun doWork(): Result {
    if (!workerLock.tryLock()) return Result.success()
    DbManager.initialize(applicationContext)
    return try {
      val completed = withTimeoutOrNull(8 * 60 * 1000L) {
        DownloadServiceHost.acquireBackground(applicationContext)
        if (DownloadAutomation.networkAllowed(applicationContext, false)) {
          suspendCancellableCoroutine<Unit> { continuation ->
            ApiHandler(applicationContext).syncLocalSessionsAndProgress { _, _ -> if (continuation.isActive) continuation.resume(Unit) }
          }
        }
        DownloadAutomation.replenish(applicationContext)
        DownloadServiceHost.startWork(applicationContext)
        while (DownloadServiceHost.hasAutomaticWork(applicationContext)) delay(500)
        true
      }
      FinishedDownloadCleanup(applicationContext).cleanupAutomatic()
      if (completed == null) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) { com.audiobookshelf.app.plugins.AbsLogger.error("ListeningHousekeeping", "Housekeeping deferred (${e.javaClass.simpleName})"); Result.retry() }
    finally { DownloadServiceHost.releaseBackground(applicationContext); workerLock.unlock(); ProgressStatusStore.changed() }
  }
}
