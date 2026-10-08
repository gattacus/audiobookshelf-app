package com.audiobookshelf.app

internal enum class WidgetFeedback { IDLE, STARTING, PLAYING, ERROR }

internal object WidgetFeedbackPolicy {
  fun whenPlaybackChanges(current: WidgetFeedback, matches: Boolean, isPlaying: Boolean): WidgetFeedback =
    if (matches && isPlaying) WidgetFeedback.PLAYING
    else if (current == WidgetFeedback.STARTING) WidgetFeedback.STARTING
    else WidgetFeedback.IDLE

  fun whenTimedOut(current: WidgetFeedback): WidgetFeedback = when (current) {
    WidgetFeedback.STARTING -> WidgetFeedback.ERROR
    WidgetFeedback.ERROR -> WidgetFeedback.IDLE
    else -> current
  }
}
