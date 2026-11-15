package com.audiobookshelf.app.media

import org.junit.Assert.*
import org.junit.Test

class AndroidAutoLoadCoordinatorTest {
  private class Harness {
    var time = 0L
    var account = "server-a/user-a"
    val events = mutableListOf<String>()
    val queued = mutableListOf<() -> Unit>()
    val timers = mutableListOf<Pair<Long, () -> Unit>>()
    val loader = AndroidAutoLoadCoordinator(
      { account }, { time }, { queued.add(it) },
      { delay, action ->
        val task = time + delay to action
        timers.add(task)
        val cancel: () -> Unit = { timers.remove(task); Unit }
        cancel
      }, { events.add(it) }, timeoutMs = 100
    )

    fun drain() { while (queued.isNotEmpty()) queued.removeAt(0)() }
    fun advance(ms: Long) {
      time += ms
      timers.toList().filter { it.first <= time }.forEach { it.second() }
      drain()
    }
  }

  @Test fun everyNetworkStageCanTimeOutAndReopeningCanRetry() {
    for (stage in listOf("ping[0]", "progress_sync", "authorize", "libraries", "personalized")) {
      val h = Harness()
      val outcomes = mutableListOf<Boolean>()
      h.loader.load({ outcomes.add(it) }) { load -> load.stage<Unit>(stage, { /* no callback */ }) {} }
      h.advance(100)
      assertEquals(listOf(false), outcomes)
      assertTrue(h.events.any { it.contains("stage=$stage outcome=timeout") })
      h.loader.load({ outcomes.add(it) }) { it.complete(true, "loaded") }
      assertEquals(listOf(false, true), outcomes)
      assertTrue(h.timers.isEmpty())
    }
  }

  @Test fun overlappingBrowsersShareOneLoadAndEachCompletesOnce() {
    val h = Harness()
    val outcomes = mutableListOf<Boolean>()
    var starts = 0
    lateinit var response: (Boolean) -> Unit
    h.loader.load({ outcomes.add(it) }) { load ->
      starts++
      load.stage<Boolean>("ping", { response = it }) { load.complete(it, "loaded") }
    }
    h.loader.load({ outcomes.add(it) }) { starts++ }
    response(true)
    response(false)
    assertTrue(outcomes.isEmpty()) // Network callbacks must dispatch before touching browse state.
    h.drain()
    h.advance(100)
    assertEquals(1, starts)
    assertEquals(listOf(true, true), outcomes)
  }

  @Test fun lateSuccessAfterTimeoutCannotReplaceTheRetrySnapshot() {
    val h = Harness()
    lateinit var oldResponse: (String) -> Unit
    var snapshot = "cached"
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it) }) { load ->
      load.stage<String>("libraries", { oldResponse = it }) { snapshot = it; load.complete(true, "loaded") }
    }
    h.advance(100)
    assertEquals("cached", snapshot)
    h.loader.load({ outcomes.add(it) }) { load ->
      load.stage<String>("libraries", { it("new") }) { snapshot = it; load.complete(true, "loaded") }
    }
    h.drain()
    oldResponse("obsolete")
    h.drain()
    assertEquals("new", snapshot)
    assertEquals(listOf(false, true), outcomes)
  }

  @Test fun accountSwitchRejectsOldResponseEvenBeforeNewBrowserConnects() {
    val h = Harness()
    lateinit var response: (String) -> Unit
    var committed = false
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it) }) { load ->
      load.stage<String>("authorize", { response = it }) { committed = true }
    }
    h.account = "server-a/user-b"
    response("old progress")
    h.drain()
    assertFalse(committed)
    assertEquals(listOf(false), outcomes)
    assertTrue(h.events.any { it.contains("outcome=context_changed") })
  }

  @Test fun newServerStartsWithoutWaitingForOldLoadAndLateCallbackIsIgnored() {
    val h = Harness()
    lateinit var response: (Unit) -> Unit
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it) }) { load -> load.stage<Unit>("libraries", { response = it }) { fail() } }
    h.account = "server-b/user-a"
    h.loader.load({ outcomes.add(it) }) { it.complete(true, "loaded") }
    response(Unit)
    h.drain()
    assertEquals(listOf(false, true), outcomes)
  }

  @Test fun queuedResponseCannotCommitAfterDeadlineWhenTimerHasNotRunYet() {
    val h = Harness()
    h.loader.load({ assertFalse(it) }) { load ->
      load.stage<Unit>("libraries", { it(Unit) }) { fail("Expired response committed") }
    }
    h.time = 100
    h.drain()
    assertTrue(h.timers.isEmpty())
  }

  @Test fun duplicateStageCallbackCannotAdvancePipelineTwice() {
    val h = Harness()
    var authorizationRequests = 0
    lateinit var ping: (Boolean) -> Unit
    h.loader.load({}) { load ->
      load.stage<Boolean>("ping", { ping = it }) {
        load.stage<Unit>("authorize", { authorizationRequests++ }) {}
      }
    }
    ping(true)
    h.drain()
    ping(true)
    h.drain()
    assertEquals(1, authorizationRequests)
  }

  @Test fun exceptionsReleaseLoadAndDoNotExposeExceptionMessages() {
    val h = Harness()
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it); error("callback failure") }) { load ->
      load.stage<Unit>("libraries", { it(Unit) }) { error("private credential") }
    }
    h.loader.load({ outcomes.add(it) }) { fail("Should coalesce") }
    h.drain()
    assertEquals(listOf(false, false), outcomes)
    assertFalse(h.events.any { "private credential" in it || "callback failure" in it })
    h.loader.load({ outcomes.add(it) }) { it.complete(true, "loaded") }
    assertEquals(listOf(false, false, true), outcomes)
  }

  @Test fun unreachableServerAndValidatedFallbackBothReleaseTheLoad() {
    val h = Harness()
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it) }) { load ->
      load.stage<Boolean>("ping[0]", { it(false) }) { load.complete(false, "unreachable") }
    }
    h.drain()
    h.loader.load({ outcomes.add(it) }) { load ->
      load.stage<Boolean>("ping[1]", { it(true) }) {
        load.adoptContext { h.account = "fallback/user-a" }
        load.stage<Unit>("libraries", { it(Unit) }) { load.complete(true, "loaded") }
      }
    }
    h.drain()
    assertEquals(listOf(false, true), outcomes)
  }

  @Test fun invalidationReleasesEveryCallerAndCancelsDeadline() {
    val h = Harness()
    val outcomes = mutableListOf<Boolean>()
    h.loader.load({ outcomes.add(it) }) {}
    h.loader.load({ outcomes.add(it) }) {}
    h.loader.invalidate()
    h.loader.invalidate()
    h.advance(100)
    assertEquals(listOf(false, false), outcomes)
    assertTrue(h.timers.isEmpty())
  }
}
