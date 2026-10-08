package com.audiobookshelf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetEpisodeSelectorTest {
  private fun episode(id: String, date: Long, finished: Boolean = false, time: Double = 0.0, updated: Long = 0) =
    WidgetEpisodeSelector.Episode(id, date, 0, finished, time, updated)

  @Test fun resumesMostRecentlyPlayedUnfinishedEpisode() {
    val episodes = listOf(episode("old", 1, time = 120.0, updated = 10), episode("new", 3, time = 30.0, updated = 20), episode("newest", 4))
    assertEquals("new", WidgetEpisodeSelector.select(episodes, false)?.id)
  }

  @Test fun usesShowOrderThenReplaysFirstWhenFinished() {
    val episodes = listOf(episode("old", 1, finished = true), episode("middle", 2), episode("new", 3))
    assertEquals("middle", WidgetEpisodeSelector.select(episodes, true)?.id)
    assertEquals("new", WidgetEpisodeSelector.select(episodes, false)?.id)
    assertEquals("old", WidgetEpisodeSelector.select(episodes.map { it.copy(isFinished = true) }, true)?.id)
    assertEquals("new", WidgetEpisodeSelector.select(episodes.map { it.copy(isFinished = true) }, false)?.id)
    assertNull(WidgetEpisodeSelector.select(emptyList(), false))
  }

  @Test fun excludedDownloadsCannotBeSelected() {
    val available = listOf(episode("downloaded-old", 1, time = 5.0, updated = 1), episode("downloaded-new", 3))
    assertEquals("downloaded-old", WidgetEpisodeSelector.select(available, false)?.id)
  }
}
