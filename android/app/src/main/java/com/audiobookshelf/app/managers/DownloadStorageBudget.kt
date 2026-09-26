package com.audiobookshelf.app.managers

import kotlin.math.max

/** Distinguishes actual storage shortage from space temporarily reserved by another transfer. */
object DownloadStorageBudget {
  data class Space(val availableBytes: Long, val totalBytes: Long)
  enum class Result { AVAILABLE, RESERVED, INSUFFICIENT }
  data class Decision(val result: Result, val requiredFreeBytes: Long, val reservationBytes: Long)

  fun evaluate(space: Space, fileSize: Long, stagedBytes: Long, internal: Boolean,
          alreadyReserved: Long): Decision {
    val expected = if (fileSize > 0L) fileSize else 100L * 1024L * 1024L
    val remaining = (expected - stagedBytes).coerceAtLeast(0L)
    val required = if (internal) remaining else remaining + expected
    val minimumFree = required + headroom(space)
    val result = when {
      space.availableBytes < minimumFree -> Result.INSUFFICIENT
      space.availableBytes - alreadyReserved < minimumFree -> Result.RESERVED
      else -> Result.AVAILABLE
    }
    return Decision(result, minimumFree, required)
  }

  fun headroom(space: Space): Long = max(100L * 1024L * 1024L, space.totalBytes / 20L)
}
