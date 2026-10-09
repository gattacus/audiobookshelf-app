package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.data.PodcastEpisode

/** Only immutable, validated identities are persisted; playback candidates are rebuilt from media. */
internal class PodcastPlaybackPreferences(
  private val read: (String) -> Boolean?,
  private val write: (String, Boolean) -> Unit
) {
  constructor(context: Context) : this(
    { key -> context.getSharedPreferences("podcastPlayback", Context.MODE_PRIVATE).all[key] as? Boolean },
    { key, enabled -> context.getSharedPreferences("podcastPlayback", Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply() }
  )

  fun get(key: String): Boolean? = read(key)
  fun set(key: String, enabled: Boolean) = write(key, enabled)

  companion object {
    fun key(serverConfigId: String?, serverItemId: String?, localItemId: String?): String? {
      if (!serverConfigId.isNullOrBlank() && !serverItemId.isNullOrBlank()) {
        return "server:${serverConfigId.length}:$serverConfigId:$serverItemId"
      }
      return localItemId?.takeIf { it.isNotBlank() }?.let { "local:$it" }
    }
  }
}

internal object PodcastPlaybackPolicy {
  data class Episode(val id: String, val publishedAt: Long?, val index: Int, val finished: Boolean, val available: Boolean = true)

  fun validEpisodes(episodes: List<PodcastEpisode>?): List<PodcastEpisode> = episodes.orEmpty().filter {
    // Paper can restore null fields/elements that Kotlin constructors would never accept.
    runCatching { it.id.isNotBlank() }.getOrDefault(false)
  }

  fun next(episodes: List<Episode>, currentId: String, serial: Boolean): Episode? {
    val valid = episodes.filter { it.id.isNotBlank() }.distinctBy { it.id }
    // Stable sorting preserves the source order when the server supplies neither dates nor indices.
    // Episode UUIDs carry no ordering information and must never decide which episode plays next.
    val ordered = if (serial) valid.sortedWith(compareBy<Episode> { it.publishedAt ?: Long.MAX_VALUE }.thenBy { it.index })
      else valid.sortedWith(compareByDescending<Episode> { it.publishedAt ?: Long.MIN_VALUE }.thenBy { it.index })
    val index = ordered.indexOfFirst { it.id == currentId }
    // A deleted/malformed current entry cannot establish a safe continuation point. Never wrap.
    if (index < 0) return null
    return ordered.drop(index + 1).firstOrNull { !it.finished && it.available }
  }
}

/** Reject duplicate ENDED events and async replies after pause, close, or another play request. */
internal class PodcastAdvanceRequests {
  private var revision = 0
  private var endedSession: String? = null
  fun begin(sessionId: String): Int? {
    if (endedSession == sessionId) return null
    endedSession = sessionId
    return ++revision
  }
  fun isCurrent(request: Int) = revision == request
  fun cancel() { revision++; endedSession = null }
}
