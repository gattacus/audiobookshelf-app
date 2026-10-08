package com.audiobookshelf.app.media

import org.junit.Assert.*
import org.junit.Test

class AndroidAutoBrowseCacheTest {
  @Test fun failedInitialLoadRetriesAndRecoversWithoutReconnect() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    cache.complete(cache.beginLoad(0)!!, null, 100)
    assertFalse(cache.hasValue)
    assertNull(cache.beginLoad(5_099))
    assertEquals(1L, cache.nextLoadDelayMs(5_099))
    cache.complete(cache.beginLoad(5_100)!!, listOf("resume-book"), 5_200)
    assertEquals(listOf("resume-book"), cache.value)
    assertTrue(cache.hasValue)
  }

  @Test fun refreshFailurePreservesPreviousContinueItems() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    cache.complete(cache.beginLoad(0)!!, listOf("book", "episode"), 100)
    cache.complete(cache.beginLoad(60_100)!!, null, 60_200)
    assertEquals(listOf("book", "episode"), cache.value)
    assertTrue(cache.hasValue)
    assertEquals(5_000L, cache.nextLoadDelayMs(60_200))
  }

  @Test fun genuinelyEmptySuccessRemovesCompletedItemsAndIsCached() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    cache.complete(cache.beginLoad(0)!!, listOf("book"), 0)
    cache.complete(cache.beginLoad(60_000)!!, emptyList(), 60_100)
    assertEquals(emptyList<String>(), cache.value)
    assertTrue(cache.hasValue)
    assertNull(cache.beginLoad(60_101))
    assertEquals(60_000L, cache.nextLoadDelayMs(60_100))
  }

  @Test fun overlappingRootAndDiscoveryRequestsAreCoalesced() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    val request = cache.beginLoad(0)!!
    assertNull(cache.beginLoad(1))
    assertNull(cache.beginLoad(90_000))
    cache.complete(request, listOf("book"), 90_001)
    assertNull(cache.beginLoad(90_002))
  }

  @Test fun repeatedFailuresBackOffWithACapAndSuccessResetsBackoff() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    var now = 0L
    for (delay in listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L)) {
      cache.complete(cache.beginLoad(now)!!, null, now)
      assertEquals(delay, cache.nextLoadDelayMs(now))
      now += delay
    }
    cache.complete(cache.beginLoad(now)!!, emptyList(), now)
    now += 60_000
    cache.complete(cache.beginLoad(now)!!, null, now)
    assertEquals(5_000L, cache.nextLoadDelayMs(now))
  }

  @Test fun staleOrDuplicateCompletionCannotOverwriteCurrentSnapshot() {
    val cache = AndroidAutoBrowseCache<List<String>>(emptyList())
    val first = cache.beginLoad(0)!!
    assertFalse(cache.complete(first + 1, listOf("wrong"), 0))
    assertTrue(cache.complete(first, listOf("book"), 0))
    val second = cache.beginLoad(60_000)!!
    assertFalse(cache.complete(first, emptyList(), 60_000))
    assertTrue(cache.complete(second, listOf("new-book"), 60_001))
    assertFalse(cache.complete(second, null, 60_002))
    assertEquals(listOf("new-book"), cache.value)
  }

  @Test fun oneLibraryFailureDoesNotBlockAnotherLibraryOrLeakAcrossServers() {
    val failedLibrary = AndroidAutoBrowseCache<List<String>>(emptyList())
    val availableLibrary = AndroidAutoBrowseCache<List<String>>(emptyList())
    failedLibrary.complete(failedLibrary.beginLoad(0)!!, null, 0)
    availableLibrary.complete(availableLibrary.beginLoad(0)!!, listOf("book"), 0)
    assertEquals(listOf("book"), availableLibrary.value)
    assertEquals(5_000L, failedLibrary.nextLoadDelayMs(0))
    val newServerLibrary = AndroidAutoBrowseCache<List<String>>(emptyList())
    assertFalse(newServerLibrary.hasValue)
    assertEquals(emptyList<String>(), newServerLibrary.value)
  }
}
