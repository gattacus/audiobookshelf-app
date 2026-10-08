package com.audiobookshelf.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFeedbackPolicyTest {
  @Test fun tappedWidgetStaysLoadingUntilItsAudioStarts() {
    assertEquals(WidgetFeedback.STARTING,
      WidgetFeedbackPolicy.whenPlaybackChanges(WidgetFeedback.STARTING, false, true))
    assertEquals(WidgetFeedback.STARTING,
      WidgetFeedbackPolicy.whenPlaybackChanges(WidgetFeedback.STARTING, true, false))
    assertEquals(WidgetFeedback.PLAYING,
      WidgetFeedbackPolicy.whenPlaybackChanges(WidgetFeedback.STARTING, true, true))
  }

  @Test fun otherWidgetsReturnToPlayWhenPlaybackSwitchesOrPauses() {
    assertEquals(WidgetFeedback.IDLE,
      WidgetFeedbackPolicy.whenPlaybackChanges(WidgetFeedback.PLAYING, false, true))
    assertEquals(WidgetFeedback.IDLE,
      WidgetFeedbackPolicy.whenPlaybackChanges(WidgetFeedback.PLAYING, true, false))
  }

  @Test fun stalledAndFailedStatesRecover() {
    assertEquals(WidgetFeedback.ERROR, WidgetFeedbackPolicy.whenTimedOut(WidgetFeedback.STARTING))
    assertEquals(WidgetFeedback.IDLE, WidgetFeedbackPolicy.whenTimedOut(WidgetFeedback.ERROR))
  }
}
