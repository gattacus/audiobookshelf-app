package com.audiobookshelf.app.listening

import com.audiobookshelf.app.player.PodcastPlaybackPolicy

const val GIB = 1024L * 1024L * 1024L

data class PodcastDownloadRule(val identity: ListeningIdentity, val enabled: Boolean = false, val count: Int = 3,
  val folderId: String = "internal-podcast", val wifiOnly: Boolean = true, val anchor: String? = null,
  val status: String = "Not checked yet", val checkedAt: Long = 0, val title: String = "Podcast")
data class DownloadHousekeepingSettings(val automaticLimitBytes: Long = 2 * GIB,
  val cleanBooks: Boolean = false, val cleanEpisodes: Boolean = false,
  val bookDelayHours: Int = 24, val episodeDelayHours: Int = 24)
data class OwnedAudio(val identity: ListeningIdentity, val localItemId: String, val localEpisodeId: String?,
  val fileId: String, val contentUrl: String, val path: String, val folderId: String, val bytes: Long,
  val automatic: Boolean = false, val removedAt: Long? = null, val deleting: Boolean = false) {
  val key: String get() = ListeningStorageKeys.compact("${identity.rawKey}:${fileId.length}:$fileId")
}
data class AutomaticTransfer(val identity: ListeningIdentity, val wifiOnly: Boolean, val reservedBytes: Long)

internal object NextDownloadPolicy {
  fun targets(episodes: List<PodcastPlaybackPolicy.Episode>, anchor: String?, serial: Boolean, count: Int): List<String> {
    val valid = episodes.filter { it.id.isNotBlank() }.distinctBy { it.id }
    val ordered = if (serial) valid.sortedWith(compareBy<PodcastPlaybackPolicy.Episode> { it.publishedAt ?: Long.MAX_VALUE }.thenBy { it.index })
      else valid.sortedWith(compareByDescending<PodcastPlaybackPolicy.Episode> { it.publishedAt ?: Long.MIN_VALUE }.thenBy { it.index })
    val start = if (anchor == null) 0 else ordered.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: return emptyList()
    return ordered.drop(start).filter { !it.finished && it.available }.take(count.coerceIn(1, 100)).map { it.id }
  }
}

object FinishedCleanupPolicy {
  fun reason(finished: Boolean, protected: Boolean, active: Boolean, transferring: Boolean, verified: Boolean,
    automatic: Boolean, confirmed: Boolean, pending: Int, finishedAt: Long, now: Long, delayHours: Int): String? = when {
    !finished -> "Not finished"
    protected -> "Keep downloaded"
    active -> "In use by player"
    transferring -> "Download in progress"
    !verified -> "Download ownership could not be verified"
    automatic && (!confirmed || pending > 0) -> "Waiting for confirmed progress sync"
    automatic && now - finishedAt < delayHours * 3600000L -> "Grace period"
    else -> null
  }
}

object AutomaticStorageBudget {
  fun canReserve(limit: Long, downloaded: Long, reserved: Long, incoming: Long): Boolean {
    if (downloaded < 0 || reserved < 0 || incoming <= 0) return false
    if (limit == 0L) return true
    if (downloaded > limit || reserved > limit - downloaded) return false
    return incoming <= limit - downloaded - reserved
  }
}
