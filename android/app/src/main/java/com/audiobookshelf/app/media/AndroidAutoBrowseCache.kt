package com.audiobookshelf.app.media

/** A failed browse request must not replace a successful snapshot with an empty list. */
internal class AndroidAutoBrowseCache<T>(private val emptyValue: T) {
  var value: T = emptyValue
    private set
  var hasValue = false
    private set
  private var requestId = 0L
  private var inFlight = false
  private var nextLoadAt = 0L
  private var retryDelayMs = 5_000L

  fun beginLoad(now: Long): Long? {
    if (inFlight || now < nextLoadAt) return null
    inFlight = true
    return ++requestId
  }

  fun complete(id: Long, result: T?, now: Long): Boolean {
    if (!inFlight || id != requestId) return false
    inFlight = false
    if (result == null) {
      nextLoadAt = now + retryDelayMs
      retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    } else {
      value = result
      hasValue = true
      retryDelayMs = 5_000L
      nextLoadAt = now + 60_000L
    }
    return true
  }

  fun nextLoadDelayMs(now: Long): Long =
    if (inFlight) 1_000L else (nextLoadAt - now).coerceAtLeast(0L)
}
