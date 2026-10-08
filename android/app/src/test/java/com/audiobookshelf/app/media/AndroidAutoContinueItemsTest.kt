package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.*
import org.junit.Test

class AndroidAutoContinueItemsTest {
  private val mapper = jacksonObjectMapper()
  private fun book(id: String, tracks: Int = 1): String = """
    {"id":"$id","ino":"1","libraryId":"books","folderId":"folder","path":"/books/$id",
     "relPath":"$id","mediaType":"book","media":{"numTracks":$tracks,"tags":[],
     "metadata":{"title":"$id","genres":[],"authors":[],"narrators":[]}}}
  """.trimIndent()
  private fun podcast(episodeId: String? = "episode-1"): String = """
    {"id":"show","ino":"2","libraryId":"podcasts","folderId":"folder","path":"/podcasts/show",
     "relPath":"show","mediaType":"podcast","media":{"numEpisodes":2,"autoDownloadEpisodes":false,"tags":[],
     "metadata":{"title":"Show","genres":[],"feedUrl":"https://example.test/feed"}},
     "recentEpisode":${episodeId?.let { """{"id":"$it","index":1,"title":"Episode"}""" } ?: "null"}}
  """.trimIndent()
  private fun shelf(id: String, type: String, items: List<String>): String = """
    {"id":"$id","label":"Shelf","total":${items.size},"type":"$type","entities":[${items.joinToString(",")}]}
  """.trimIndent()
  private fun parse(vararg shelves: String): List<LibraryShelfType> =
    AndroidAutoShelfResponse.parse("""{"value":[${shelves.joinToString(",")}] }""", mapper)!!
  private fun progress(item: String, episode: String?, lastUpdate: Long) =
    MediaProgress("progress", item, episode, 100.0, 0.2, 20.0, false, null, null, lastUpdate, 0, null)

  @Test fun continueUsesVisibleShelfEvenWhenSeparateProgressEndpointIsEmpty() {
    val shelves = parse(shelf("continue-listening", "book", listOf(book("resume"))))
    val items = AndroidAutoContinueItems.fromShelves(shelves, emptyList())
    assertEquals(listOf("resume"), items.map { it.libraryItemWrapper.id })
  }

  @Test fun recentDiscoveryAndReadingShelvesAreNotResumeHistory() {
    val shelves = parse(
      shelf("recently-added", "book", listOf(book("new"))),
      shelf("discover", "book", listOf(book("suggested"))),
      shelf("continue-reading", "book", listOf(book("ebook"))),
      shelf("continue-listening", "book", listOf(book("audio"), book("no-audio", 0)))
    )
    assertEquals(listOf("audio"), AndroidAutoContinueItems.fromShelves(shelves, emptyList()).map { it.libraryItemWrapper.id })
  }

  @Test fun podcastResumeTargetsSpecificEpisodeAndExcludesMissingEpisode() {
    val shelves = parse(shelf("continue-listening", "episode", listOf(podcast(), podcast(null))))
    val items = AndroidAutoContinueItems.fromShelves(shelves, listOf(progress("show", "episode-1", 123)))
    assertEquals(1, items.size)
    assertEquals("episode-1", items.single().episode!!.id)
    assertEquals(123L, items.single().progressLastUpdate)
  }

  @Test fun compactPodcastShelfDoesNotRequireParentEpisodeCountOrTracks() {
    // The server's personalized episode shelf omits episodes and numEpisodes on the parent.
    val compactPodcast = podcast().replace("\"numEpisodes\":2,", "")
    val shelves = parse(shelf("continue-listening", "episode", listOf(compactPodcast)))
    assertFalse(shelves.let { (it.single() as LibraryShelfEpisodeEntity).entities!!.single().checkHasTracks() })
    val items = AndroidAutoContinueItems.fromShelves(shelves, emptyList())
    assertEquals("episode-1", items.single().episode!!.id)
  }

  @Test fun emptyParentEpisodeListDoesNotHideThePlayableRecentEpisode() {
    val compactPodcast = podcast().replace("\"numEpisodes\":2", "\"episodes\":[],\"numEpisodes\":2")
    val shelves = parse(shelf("continue-listening", "episode", listOf(compactPodcast)))
    assertEquals("episode-1", AndroidAutoContinueItems.fromShelves(shelves, emptyList()).single().episode!!.id)
  }

  @Test fun zeroParentEpisodeCountDoesNotHideThePlayableRecentEpisode() {
    // Minified shelves can report zero when the parent show's full episodes were not loaded.
    val compactPodcast = podcast().replace("\"numEpisodes\":2", "\"numEpisodes\":0")
    val shelves = parse(shelf("continue-listening", "episode", listOf(compactPodcast)))
    assertFalse((shelves.single() as LibraryShelfEpisodeEntity).entities!!.single().checkHasTracks())
    assertEquals("episode-1", AndroidAutoContinueItems.fromShelves(shelves, emptyList()).single().episode!!.id)
  }

  @Test fun podcastWithBlankEpisodeTargetIsExcluded() {
    val shelves = parse(shelf("continue-listening", "episode", listOf(podcast(" "))))
    assertTrue(AndroidAutoContinueItems.fromShelves(shelves, emptyList()).isEmpty())
  }

  @Test fun mergingLibrariesSortsByProgressAndDeduplicatesItemEpisodePairs() {
    val books = AndroidAutoContinueItems.fromShelves(
      parse(shelf("continue-listening", "book", listOf(book("book")))), listOf(progress("book", null, 10)))
    val episodes = AndroidAutoContinueItems.fromShelves(
      parse(shelf("continue-listening", "episode", listOf(podcast("ep-1"), podcast("ep-2")))),
      listOf(progress("show", "ep-1", 30), progress("show", "ep-2", 20)))
    val merged = AndroidAutoContinueItems.merge(listOf(books, episodes, books))
    assertEquals(listOf("ep-1", "ep-2", null), merged.map { it.episode?.id })
    assertEquals(3, merged.size)
  }

  @Test fun emptyResponseDiffersFromFailedOrMalformedResponse() {
    assertEquals(emptyList<LibraryShelfType>(), AndroidAutoShelfResponse.parse("""{"value":[]}""", mapper))
    for (response in listOf("""{"error":"Failed to connect"}""", "{}", "null", "{", """{"value":{}}""",
      """{"value":[{"id":"broken","type":"book"}]}""")) {
      assertNull(response, AndroidAutoShelfResponse.parse(response, mapper))
    }
  }

  @Test fun completedOrHiddenItemsStayAbsentFromAuthoritativeShelf() {
    val shelves = parse(shelf("continue-listening", "book", emptyList()))
    val items = AndroidAutoContinueItems.fromShelves(shelves, listOf(progress("hidden", null, 100)))
    assertTrue(items.isEmpty())
  }
}
