package com.audiobookshelf.app

import android.content.Context
import android.appwidget.AppWidgetManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.google.android.exoplayer2.Player

/** Playback entry point for a widget tap. All work uses the widget's own saved identity. */
internal class WidgetPlayback(private val service: PlayerNotificationService) {
  private val db = DeviceManager.dbManager
  private val handler = Handler(Looper.getMainLooper())
  private val requests = WidgetPlaybackRequests()
  private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

  fun cancel() {
    requests.cancel()
  }

  fun play(target: WidgetTarget, id: Int) {
    val currentRequest = requests.begin()
    widgetId = id
    val active = service.currentPlaybackSession
    if (active != null && service.currentPlayer.mediaItemCount > 0 &&
      service.currentPlayer.playbackState != Player.STATE_ENDED &&
      WidgetTargetMatcher.matches(target, active.mediaType, active.libraryItemId,
        active.serverConnectionConfigId, active.localLibraryItem?.id)) {
      if (!service.currentPlayer.isPlaying) service.play()
      else ItemPlaybackWidget.syncPlayback(service)
      return
    }
    val local = target.localItemId?.let { runCatching { db.getLocalLibraryItem(it) }.getOrNull() }?.takeIf {
      it.mediaType == target.mediaType && (target.serverItemId == null ||
        (it.libraryItemId == target.serverItemId && it.serverConnectionConfigId == target.serverConfigId))
    }
    if (target.mediaType == "book") {
      if (local != null && safeHasTracks(local, null) && prepareLocal(local, null, currentRequest)) {
        return
      } else playServer(target, local, currentRequest)
    } else playServer(target, local, currentRequest)
  }

  private fun playServer(target: WidgetTarget, local: LocalLibraryItem?, currentRequest: Int) {
    val config = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == target.serverConfigId }
    if (target.serverItemId == null || config == null || !DeviceManager.checkConnectivity(service)) {
      playLocal(target, local, currentRequest)
      return
    }
    if (!streamingAllowed()) {
      playLocal(target, local, currentRequest, "Open Audiobookshelf to allow cellular streaming")
      return
    }
    service.apiHandler.pingServer(config) { reachable ->
      if (!requests.isCurrent(currentRequest)) return@pingServer
      if (!reachable) {
        playLocal(target, local, currentRequest)
      } else {
        DeviceManager.serverConnectionConfig = config
        service.apiHandler.syncLocalSessionsAndProgress { _, _ ->
          if (!requests.isCurrent(currentRequest)) return@syncLocalSessionsAndProgress
          if (target.mediaType == "book") {
            service.apiHandler.playLibraryItem(target.serverItemId, null, service.getPlayItemRequestPayload(false)) { session ->
              if (session != null) prepare(session, currentRequest) else playLocal(target, local, currentRequest)
            }
          } else {
            service.apiHandler.authorize(config) { progress ->
              if (!requests.isCurrent(currentRequest)) return@authorize
              if (progress == null) {
                playLocal(target, local, currentRequest)
              } else service.apiHandler.getLibraryItem(target.serverItemId) { item ->
                if (!requests.isCurrent(currentRequest)) return@getLibraryItem
                val podcast = item?.media as? Podcast
                val episodes = podcast?.episodes.orEmpty()
                val selected = selectServerEpisode(episodes, progress, target, local)
                if (selected == null) {
                  playLocal(target, local, currentRequest)
                  return@getLibraryItem
                }
                val localEpisode = local?.let { lli ->
                  (lli.media as? Podcast)?.episodes?.find { it.serverEpisodeId == selected.id && safeHasTracks(lli, it) }
                }
                if (localEpisode != null && prepareLocal(local, localEpisode, currentRequest)) {
                  return@getLibraryItem
                } else service.apiHandler.playLibraryItem(target.serverItemId, selected.id, service.getPlayItemRequestPayload(false)) { session ->
                  if (session != null) prepare(session, currentRequest) else playLocal(target, local, currentRequest)
                }
              }
            }
          }
        }
      }
    }
  }

  private fun selectServerEpisode(episodes: List<PodcastEpisode>, progress: List<MediaProgress>, target: WidgetTarget, local: LocalLibraryItem?): PodcastEpisode? {
    val localEpisodes = (local?.media as? Podcast)?.episodes.orEmpty()
    val choices = episodes.map { episode ->
      val remote = progress.find { it.libraryItemId == target.serverItemId && it.episodeId == episode.id }
      val downloaded = localEpisodes.find { it.serverEpisodeId == episode.id }
      val saved = downloaded?.let { runCatching { db.getLocalMediaProgress("${local?.id}-${it.id}") }.getOrNull() }
      val useSaved = saved != null && saved.lastUpdate > (remote?.lastUpdate ?: 0L)
      WidgetEpisodeSelector.Episode(episode.id, target.dateFor(episode), episode.index,
        if (useSaved) saved!!.isFinished else remote?.isFinished ?: false,
        if (useSaved) saved!!.currentTime else remote?.currentTime ?: 0.0,
        if (useSaved) saved!!.lastUpdate else remote?.lastUpdate ?: 0L)
    }
    val id = WidgetEpisodeSelector.select(choices, target.podcastType == "serial")?.id
    return episodes.find { it.id == id }
  }

  private fun playLocal(target: WidgetTarget, local: LocalLibraryItem?, currentRequest: Int, failureMessage: String = "No playable audio available") {
    if (!requests.isCurrent(currentRequest)) return
    if (local == null) return fail(target, failureMessage, currentRequest)
    if (target.mediaType == "book") {
      if (safeHasTracks(local, null) && prepareLocal(local, null, currentRequest)) return
      else fail(target, failureMessage, currentRequest)
      return
    }
    val podcast = local.media as? Podcast ?: return fail(target, failureMessage, currentRequest)
    val available = podcast.episodes.orEmpty().filter { safeHasTracks(local, it) }
    val candidates = available.map { episode ->
      val progress = runCatching { db.getLocalMediaProgress("${local.id}-${episode.id}") }.getOrNull()
      WidgetEpisodeSelector.Episode(episode.id, target.dateFor(episode), episode.index,
        progress?.isFinished ?: false, progress?.currentTime ?: 0.0, progress?.lastUpdate ?: 0L)
    }
    val id = WidgetEpisodeSelector.select(candidates, target.podcastType == "serial")?.id
    val episode = available.find { it.id == id }
    if (episode == null) fail(target, failureMessage, currentRequest)
    else if (!prepareLocal(local, episode, currentRequest)) fail(target, failureMessage, currentRequest)
  }

  private fun safeHasTracks(local: LocalLibraryItem, episode: PodcastEpisode?): Boolean =
    runCatching { local.hasTracks(service, episode) }.getOrDefault(false)

  private fun prepareLocal(local: LocalLibraryItem, episode: PodcastEpisode?, currentRequest: Int): Boolean {
    val session = runCatching { local.getPlaybackSession(episode, service.getDeviceInfo()) }.getOrNull() ?: return false
    prepare(session, currentRequest)
    return true
  }

  private fun streamingAllowed(): Boolean {
    val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    val cellular = network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    return !cellular || DeviceManager.deviceData.deviceSettings?.streamingUsingCellular == StreamingUsingCellularSetting.ALWAYS
  }

  private fun prepare(session: PlaybackSession, currentRequest: Int) {
    handler.post {
      if (!requests.isCurrent(currentRequest)) return@post
      service.mediaProgressSyncer.stop {
        handler.post {
          if (requests.isCurrent(currentRequest)) {
            service.preparePlayer(session, true, service.mediaManager.getSavedPlaybackRate())
          }
        }
      }
    }
  }

  private fun fail(target: WidgetTarget, message: String, currentRequest: Int) {
    Log.w("WidgetPlayback", "Widget playback failed for ${target.title}: $message")
    handler.post {
      if (!requests.isCurrent(currentRequest)) return@post
      ItemPlaybackWidget.showError(service, widgetId)
      Toast.makeText(service, "${target.title}: $message", Toast.LENGTH_LONG).show()
      service.finishWidgetStartup()
    }
  }
}
