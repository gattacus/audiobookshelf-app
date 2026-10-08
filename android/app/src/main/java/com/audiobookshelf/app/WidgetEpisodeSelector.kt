package com.audiobookshelf.app

/** Selects a podcast episode without relying on the order returned by the server or Paper. */
internal object WidgetEpisodeSelector {
  data class Episode(
    val id: String,
    val publishedAt: Long?,
    val index: Int,
    val isFinished: Boolean,
    val currentTime: Double,
    val lastUpdate: Long
  )

  fun select(episodes: List<Episode>, serial: Boolean): Episode? {
    if (episodes.isEmpty()) return null
    val ordered = if (serial) {
      episodes.sortedWith(compareBy<Episode> { it.publishedAt ?: Long.MAX_VALUE }.thenBy { it.index })
    } else {
      episodes.sortedWith(compareByDescending<Episode> { it.publishedAt ?: Long.MIN_VALUE }.thenBy { it.index })
    }
    return ordered.filter { !it.isFinished && it.currentTime > 0.0 }
      .maxByOrNull { it.lastUpdate }
      ?: ordered.firstOrNull { !it.isFinished }
      ?: ordered.first()
  }
}
