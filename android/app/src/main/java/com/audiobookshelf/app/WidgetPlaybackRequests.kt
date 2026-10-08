package com.audiobookshelf.app

import java.util.concurrent.atomic.AtomicInteger

/** Network and queued UI callbacks belong only to the latest tap on a live service. */
internal class WidgetPlaybackRequests {
  private val generation = AtomicInteger()

  fun begin(): Int = generation.incrementAndGet()
  fun isCurrent(request: Int): Boolean = generation.get() == request
  fun cancel() { generation.incrementAndGet() }
}
