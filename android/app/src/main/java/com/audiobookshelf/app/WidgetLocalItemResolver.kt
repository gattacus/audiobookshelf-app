package com.audiobookshelf.app

import com.audiobookshelf.app.data.LocalLibraryItem

/** Resolve on every use: downloads can be added or replaced after a widget is pinned. */
internal object WidgetLocalItemResolver {
  fun resolve(target: WidgetTarget, items: List<LocalLibraryItem>): LocalLibraryItem? {
    val matching = items.filter { item ->
      item.mediaType == target.mediaType && !item.isInvalid &&
        if (target.serverItemId != null) {
          target.serverConfigId != null && item.libraryItemId == target.serverItemId &&
            item.serverConnectionConfigId == target.serverConfigId
        } else target.localItemId != null && item.id == target.localItemId
    }
    return matching.find { it.id == target.localItemId } ?: matching.firstOrNull()
  }
}
