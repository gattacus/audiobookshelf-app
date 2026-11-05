package com.audiobookshelf.app.player

import org.junit.Assert.*
import org.junit.Test
import com.audiobookshelf.app.data.*

class PodcastPlaybackPolicyTest {
  private fun episode(id: String, date: Long?, finished: Boolean = false, available: Boolean = true, index: Int = 0) =
    PodcastPlaybackPolicy.Episode(id, date, index, finished, available)

  @Test fun localEpisodePlaybackNeverFallsBackToOtherEpisodes() {
    val otherTrack = AudioTrack(1, 0.0, 1800.0, "other.opus", "file:///other.opus", "audio/opus", null, true, "other-file", null)
    fun record(id: String, track: AudioTrack?) =
      PodcastEpisode(id, 1, null, null, id, null, null, null, 1L, null, track, null, 1800.0, null, id, id)
    val removed = record("removed", null).apply { audioRemoved = true }
    val legacyMissing = record("legacy", null)
    val other = record("other", otherTrack)
    val podcast = Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(),
      mutableListOf(removed, legacyMissing, other), false, 3)
    val local = LocalLibraryItem("local_podcast", "internal-podcast", "", "", "", false,
      "podcast", podcast, mutableListOf(), null, null, true, null, null, null, null)
    val device = DeviceInfo("device", "Google", "Pixel", 36, "test")

    assertNull(local.getPlaybackSession(removed, device))
    assertNull(local.getPlaybackSession(legacyMissing, device))
    assertNull(local.getPlaybackSession(null, device))
    assertEquals(listOf(otherTrack), local.playbackTracks(other))
    removed.audioTrack = otherTrack // Even a stale track reference cannot make a removed episode playable.
    assertNull(local.getPlaybackSession(removed, device))
  }

  @Test fun legacyNullIdsAndElementsAreDiscardedWithoutBlockingValidEpisodes() {
    fun record(id: String) = PodcastEpisode(id, 1, null, null, null, null, null, null, 1L, null, null, null, null, null, null, null)
    val legacy = record("legacy")
    legacy.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(legacy, null)
    @Suppress("UNCHECKED_CAST")
    val restored = listOf(null, legacy, record(""), record("good")) as List<PodcastEpisode>
    assertEquals(listOf("good"), PodcastPlaybackPolicy.validEpisodes(restored).map { it.id })
    assertTrue(PodcastPlaybackPolicy.validEpisodes(null).isEmpty())
  }

  @Test fun serialContinuesOldestFirstAndSkipsFinishedEpisodes() {
    val source = listOf(episode("last", 4), episode("first", 1), episode("finished", 2, true), episode("next", 3))
    assertEquals("next", PodcastPlaybackPolicy.next(source, "first", true)?.id)
    assertNull(PodcastPlaybackPolicy.next(source, "last", true))
  }

  @Test fun episodicContinuesNewestFirstFromAnIndividuallySelectedEpisode() {
    val source = listOf(episode("old", 1), episode("new", 3), episode("newest", 4), episode("done", 2, true))
    assertEquals("old", PodcastPlaybackPolicy.next(source, "new", false)?.id)
    assertNull(PodcastPlaybackPolicy.next(source, "old", false))
  }

  @Test fun offlineSkipsUnplayableGapsButRetainsTheCurrentAnchor() {
    val source = listOf(episode("streamed-current", 4, available = false), episode("gap", 3, available = false), episode("downloaded", 2), episode("invalid-download", 1, available = false))
    assertEquals("downloaded", PodcastPlaybackPolicy.next(source, "streamed-current", false)?.id)
    assertNull(PodcastPlaybackPolicy.next(source, "downloaded", false))
    assertEquals("gap", PodcastPlaybackPolicy.next(source.map { it.copy(available = true) }, "streamed-current", false)?.id)
  }

  @Test fun deletedCurrentAndMalformedRecordsNeverRestartThePodcast() {
    val source = listOf(episode("", 4), episode("one", 3), episode("one", 3), episode("two", 2))
    assertNull(PodcastPlaybackPolicy.next(source, "deleted", false))
    assertEquals("two", PodcastPlaybackPolicy.next(source, "one", false)?.id)
    assertNull(PodcastPlaybackPolicy.next(emptyList(), "one", true))
  }

  @Test fun missingDatesAndTiesHaveDeterministicOrder() {
    val source = listOf(episode("undated", null), episode("second", 1, index = 2), episode("first", 1, index = 1))
    assertEquals("second", PodcastPlaybackPolicy.next(source, "first", true)?.id)
    assertEquals("undated", PodcastPlaybackPolicy.next(source, "second", false)?.id)
  }

  @Test fun missingDatesAndIndicesPreserveFeedOrderRatherThanUuidOrder() {
    val source = listOf(episode("b0-finished", null, finished = true), episode("af-next-downloaded", null), episode("b5-later-streamed", null))
    assertEquals("af-next-downloaded", PodcastPlaybackPolicy.next(source, "b0-finished", true)?.id)
    assertEquals("af-next-downloaded", PodcastPlaybackPolicy.next(source, "b0-finished", false)?.id)
    val tiedDates = source.map { it.copy(publishedAt = 1L) }
    assertEquals("af-next-downloaded", PodcastPlaybackPolicy.next(tiedDates, "b0-finished", true)?.id)
  }

  @Test fun choicesSurviveRecreationAndRemainScopedToPodcastAndAccount() {
    val storage = mutableMapOf<String, Boolean>()
    fun preferences() = PodcastPlaybackPreferences({ storage[it] }, { key, enabled -> storage[key] = enabled })
    val key = PodcastPlaybackPreferences.key("account-a", "show", "local-show")!!
    assertNull(preferences().get(key))
    preferences().set(key, true)
    assertEquals(true, preferences().get(PodcastPlaybackPreferences.key("account-a", "show", null)!!))
    assertNull(preferences().get(PodcastPlaybackPreferences.key("account-b", "show", null)!!))
    assertNull(preferences().get(PodcastPlaybackPreferences.key("account-a", "another-show", null)!!))
    preferences().set(key, false)
    assertEquals(false, preferences().get(key))
  }

  @Test fun unlinkedLocalPodcastsHaveIndependentKeysAndInvalidIdentityIsRejected() {
    assertEquals("local:local-one", PodcastPlaybackPreferences.key(null, null, "local-one"))
    assertNotEquals(PodcastPlaybackPreferences.key(null, null, "local-one"), PodcastPlaybackPreferences.key(null, null, "local-two"))
    assertNull(PodcastPlaybackPreferences.key(null, "show", null))
    assertNull(PodcastPlaybackPreferences.key("", "", ""))
    assertNotEquals(PodcastPlaybackPreferences.key("a:b", "c", null), PodcastPlaybackPreferences.key("a", "b:c", null))
  }

  @Test fun duplicateEndedAndCancelledRepliesCannotAdvanceTwice() {
    val requests = PodcastAdvanceRequests()
    val old = requests.begin("first")!!
    assertNull(requests.begin("first"))
    requests.cancel() // toggle off, pause, close, or a new play request
    assertFalse(requests.isCurrent(old))
    val next = requests.begin("second")!!
    assertTrue(requests.isCurrent(next))
    assertFalse(requests.isCurrent(old))
  }
}
