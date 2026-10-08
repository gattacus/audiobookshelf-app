package com.audiobookshelf.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPlaybackRequestsTest {
  @Test fun lateFailureCannotReplaceFeedbackForANewerWidgetTap() {
    val requests = WidgetPlaybackRequests()
    val feedback = mutableListOf<String>()
    val oldTap = requests.begin()
    val queuedFailure = { if (requests.isCurrent(oldTap)) feedback.add("old widget failed") }
    val newTap = requests.begin()
    val queuedPlayback = { if (requests.isCurrent(newTap)) feedback.add("new widget playing") }

    queuedPlayback()
    queuedFailure()
    assertEquals(listOf("new widget playing"), feedback)
  }

  @Test fun destroyingServiceCancelsNetworkAndQueuedPlayerCallbacks() {
    val requests = WidgetPlaybackRequests()
    val actions = mutableListOf<String>()
    val tap = requests.begin()
    val response = { if (requests.isCurrent(tap)) actions.add("prepare player") }
    val failure = { if (requests.isCurrent(tap)) actions.add("show error") }

    requests.cancel()
    response()
    failure()
    assertEquals(emptyList<String>(), actions)
  }
}
