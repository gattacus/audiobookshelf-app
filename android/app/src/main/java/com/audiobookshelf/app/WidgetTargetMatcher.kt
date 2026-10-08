package com.audiobookshelf.app

/** Match a widget's item, not a podcast episode: an active episode belongs to its show. */
internal object WidgetTargetMatcher {
  fun matches(target: WidgetTarget, mediaType: String, serverItemId: String?, serverConfigId: String?, localItemId: String?): Boolean {
    if (target.mediaType != mediaType) return false
    if (target.localItemId != null && target.localItemId == localItemId) return true
    return target.serverItemId != null && target.serverConfigId != null &&
      target.serverItemId == serverItemId && target.serverConfigId == serverConfigId
  }
}
