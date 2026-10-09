package com.audiobookshelf.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.exoplayer2.Player

/** Native continuation works from every episode entry point without a running WebView. */
internal class PodcastContinuousPlayback(private val service: PlayerNotificationService) {
  private val handler = Handler(Looper.getMainLooper())
  private val requests = PodcastAdvanceRequests()
  private var releaseTransition: (() -> Unit)? = null
  private val preferences get() = PodcastPlaybackPreferences(service)

  fun cancel() { requests.cancel(); val release = releaseTransition; releaseTransition = null; release?.invoke() }

  fun key(session: PlaybackSession) = PodcastPlaybackPreferences.key(session.serverConnectionConfigId, session.libraryItemId, session.localLibraryItem?.id)

  fun choice(session: PlaybackSession): Boolean? = key(session)?.let { preferences.get(it) }

  fun advance(session: PlaybackSession) {
    if (!session.isPodcastEpisode || choice(session) != true || !service.currentPlayer.playWhenReady || service.getMediaPlayer() != PLAYER_EXO) return
    val request = requests.begin(session.id) ?: return
    val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobookshelf:podcastAdvance")
    wake.acquire(60_000L)
    var wifi: WifiManager.WifiLock? = null
    val timeout = Runnable { if (requests.isCurrent(request)) cancel() }
    val release = {
      handler.removeCallbacks(timeout)
      if (wake.isHeld) wake.release()
      wifi?.let { if (it.isHeld) it.release() }
      if (requests.isCurrent(request)) releaseTransition = null
    }
    releaseTransition = release
    handler.postDelayed(timeout, 60_000L)

    fun active() = requests.isCurrent(request) && service.currentPlaybackSession === session &&
      service.currentPlayer.playbackState == Player.STATE_ENDED && service.currentPlayer.playWhenReady && choice(session) == true

    // Complete local progress immediately; do not wait for a server sync to move between downloads.
    service.mediaProgressSyncer.completeForContinuousPlayback(session)
    val local = runCatching {
      val own = session.localLibraryItem?.id?.let { DeviceManager.dbManager.getLocalLibraryItem(it) }
      own ?: DeviceManager.dbManager.getLocalLibraryItems("podcast").find {
        it.libraryItemId == session.libraryItemId && it.serverConnectionConfigId == session.serverConnectionConfigId
      }
    }.getOrNull()
    val config = session.serverConnectionConfigId?.let { DeviceManager.getServerConnectionConfig(it) }
    val canUseServer = config != null && config.id == DeviceManager.serverConnectionConfigId &&
      !session.libraryItemId.isNullOrBlank() && streamingAllowed()
    if (canUseServer) {
      @Suppress("DEPRECATION")
      val wifiManager = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      @Suppress("DEPRECATION")
      val lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "audiobookshelf:podcastAdvance")
      lock.acquire()
      wifi = lock
    }

    fun resolve(source: LibraryItem?, progress: List<MediaProgress>) {
      handler.post {
        if (!active()) { release(); return@post }
        val remotePodcast = source?.media as? Podcast
        val localPodcast = local?.media as? Podcast
        val localEpisodes = PodcastPlaybackPolicy.validEpisodes(localPodcast?.episodes)
        val episodes = if (remotePodcast != null) PodcastPlaybackPolicy.validEpisodes(remotePodcast.episodes) else localEpisodes
        val currentId = if (remotePodcast != null) session.episodeId ?: session.localEpisodeId
          else if (session.isLocal) session.localEpisodeId else localEpisodes.find { it.serverEpisodeId == session.episodeId }?.id
        if (currentId == null) { release(); return@post }
        val candidates = episodes.mapNotNull { episode ->
          runCatching {
            val downloaded = if (remotePodcast == null) episode else localEpisodes.find { it.serverEpisodeId == episode.id }
            val localProgress = downloaded?.let { DeviceManager.dbManager.getLocalMediaProgress("${local?.id}-${it.id}") }
            val remoteProgress = progress.find { it.libraryItemId == session.libraryItemId && it.episodeId == (episode.serverEpisodeId ?: episode.id) }
            val finished = if (localProgress != null && localProgress.lastUpdate >= (remoteProgress?.lastUpdate ?: 0L)) localProgress.isFinished else remoteProgress?.isFinished ?: false
            val downloadedPlayable = downloaded != null && local != null && safeHasTracks(local, downloaded)
            PodcastPlaybackPolicy.Episode(episode.id, episode.publishedAt, episode.index, finished,
              downloadedPlayable || (remotePodcast != null && canUseServer && episode.audioFile != null))
          }.getOrNull()
        }
        val type = (remotePodcast?.metadata as? PodcastMetadata)?.type ?: (localPodcast?.metadata as? PodcastMetadata)?.type
        val serial = if (type != null) type == "serial" else key(session)?.let { preferences.get("$it:serial") } == true
        val next = PodcastPlaybackPolicy.next(candidates, currentId, serial)
        val episode = episodes.find { it.id == next?.id }
        if (episode == null) { release(); return@post }
        val downloaded = if (remotePodcast == null) episode else localEpisodes.find { it.serverEpisodeId == episode.id }
        if (downloaded != null && local != null && safeHasTracks(local, downloaded)) {
          val nextSession = runCatching { local.getPlaybackSession(downloaded, service.getDeviceInfo()) }.getOrNull()
          if (nextSession != null && active()) {
            val remoteProgress = progress.find { it.libraryItemId == session.libraryItemId && it.episodeId == downloaded.serverEpisodeId }
            if (DeviceManager.dbManager.getLocalMediaProgress(nextSession.localMediaProgressId) == null && remoteProgress?.isFinished == false) nextSession.currentTime = remoteProgress.currentTime
            start(nextSession)
          } else release()
        } else if (canUseServer && active() && session.serverConnectionConfigId == DeviceManager.serverConnectionConfigId) {
          service.apiHandler.playLibraryItem(session.libraryItemId!!, episode.id, service.getPlayItemRequestPayload(false)) { nextSession ->
            handler.post {
              if (nextSession != null && active() && streamingAllowed() && session.serverConnectionConfigId == DeviceManager.serverConnectionConfigId) start(nextSession)
              else {
                // A request that completed after Stop or a new play request must not resume audio.
                if (nextSession != null) service.apiHandler.closePlaybackSession(nextSession.id, config) {}
                release()
                Log.w("PodcastPlayback", "Continuous playback stopped before the next episode could start")
              }
            }
          }
        } else release()
      }
    }

    if (canUseServer) {
      service.apiHandler.getLibraryItem(session.libraryItemId!!) { source ->
        handler.post {
          if (!active() || session.serverConnectionConfigId != DeviceManager.serverConnectionConfigId) { release(); return@post }
          if (source == null) resolve(session.libraryItem, service.mediaManager.serverUserMediaProgress)
          else service.apiHandler.authorize(config!!) { progress -> resolve(source, progress ?: service.mediaManager.serverUserMediaProgress) }
        }
      }
    } else resolve(session.libraryItem, emptyList())
  }

  private fun start(session: PlaybackSession) {
    val rate = service.currentPlayer.playbackParameters.speed
    PlayerListener.lazyIsPlaying = false
    PlayerListener.lastPauseTime = 0L
    service.preparePlayer(session, true, rate)
  }

  private fun safeHasTracks(item: LocalLibraryItem, episode: PodcastEpisode) = runCatching {
    // Legacy Paper fields may be null despite Kotlin declarations. Never play the whole podcast.
    val track = episode.audioTrack
    !item.isInvalid && track != null && !track.contentUrl.isNullOrBlank() && track.duration.isFinite() &&
      track.duration > 0 && episode.id.isNotBlank() && item.hasTracks(service, episode)
  }.getOrDefault(false)

  private fun streamingAllowed(): Boolean {
    if (!DeviceManager.checkConnectivity(service)) return false
    val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    val cellular = network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    return !cellular || DeviceManager.deviceData.deviceSettings?.streamingUsingCellular == StreamingUsingCellularSetting.ALWAYS
  }
}
