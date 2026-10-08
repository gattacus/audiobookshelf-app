package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.*

/** Use the server's visible Continue Listening shelves, including podcast episode targets. */
internal object AndroidAutoContinueItems {
  fun fromShelves(shelves: List<LibraryShelfType>, progress: List<MediaProgress>): List<ItemInProgress> {
    return shelves.filter { it.id == "continue-listening" }.flatMap { shelf ->
      val items = when (shelf) {
        is LibraryShelfBookEntity -> shelf.entities.orEmpty()
        is LibraryShelfEpisodeEntity -> shelf.entities.orEmpty()
        else -> emptyList()
      }
      items.mapNotNull { item ->
        val episode = if (item.mediaType == "podcast") item.recentEpisode else null
        // Episode shelves contain a playable recentEpisode but may omit the parent show's
        // episodes or report numEpisodes=0. Validate the episode target rather than the parent count.
        val playable = if (item.mediaType == "podcast") {
          episode != null && episode.id.isNotBlank()
        } else {
          item.checkHasTracks()
        }
        if (!playable) {
          null
        } else {
          val lastUpdate = progress.find {
            it.libraryItemId == item.id && it.episodeId == episode?.id
          }?.lastUpdate ?: 0L
          ItemInProgress(item, episode, lastUpdate, false)
        }
      }
    }
  }

  fun merge(libraries: List<List<ItemInProgress>>): List<ItemInProgress> =
    libraries.flatten().distinctBy { it.libraryItemWrapper.id to it.episode?.id }
      .sortedByDescending { it.progressLastUpdate }
}
