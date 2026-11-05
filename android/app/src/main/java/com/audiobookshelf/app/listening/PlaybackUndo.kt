package com.audiobookshelf.app.listening

/** Ephemeral, single-level recovery. Time is monotonic and positions are absolute item offsets. */
class PlaybackUndo {
  data class Jump(val itemKey: String, val from: Long, val to: Long, val source: String, val at: Long, val generation: Long)
  var jump: Jump? = null
    private set
  private var generation = 0L
  fun record(key: String, from: Long, to: Long, source: String, now: Long): Jump? {
    if (from == to || from < 0 || to < 0) return jump
    val previous = jump
    val grouped = previous != null && previous.itemKey == key && previous.source.endsWith("skip") && source.endsWith("skip") && now - previous.at in 0 until 2000
    jump = Jump(key, if (grouped) previous!!.from else from, to, source, now, ++generation)
    return jump
  }
  fun consume(key: String, expectedGeneration: Long): Long? {
    val value = jump ?: return null
    if (value.itemKey != key || value.generation != expectedGeneration) return null
    jump = null
    return value.from
  }
  fun select(key: String?) { if (jump?.itemKey != key) clear() }
  fun clear() { jump = null; generation++ }
}
