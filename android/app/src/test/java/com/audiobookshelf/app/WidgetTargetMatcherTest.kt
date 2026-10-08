package com.audiobookshelf.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTargetMatcherTest {
  private val book = WidgetTarget("Book", "book", "", "item-1", "server-1", "local-1")
  private val podcast = WidgetTarget("Show", "podcast", "episodic", "show-1", "server-1", "local-show-1")

  @Test fun sameBookMatchesEitherPlaybackSource() {
    assertTrue(WidgetTargetMatcher.matches(book, "book", "item-1", "server-1", null))
    assertTrue(WidgetTargetMatcher.matches(book, "book", "item-1", "server-1", "local-1"))
  }

  @Test fun samePodcastMatchesItsShowForAnyEpisode() {
    assertTrue(WidgetTargetMatcher.matches(podcast, "podcast", "show-1", "server-1", null))
    assertTrue(WidgetTargetMatcher.matches(podcast, "podcast", "show-1", "server-1", "local-show-1"))
  }

  @Test fun anotherItemOrServerDoesNotMatch() {
    assertFalse(WidgetTargetMatcher.matches(book, "book", "item-2", "server-1", "local-2"))
    assertFalse(WidgetTargetMatcher.matches(book, "book", "item-1", "server-2", null))
    assertFalse(WidgetTargetMatcher.matches(book, "podcast", "item-1", "server-1", "local-1"))
  }
}
