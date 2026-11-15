package com.audiobookshelf.app.media

/** Serializes browse callbacks, bounds the whole load, and rejects expired/account-stale work.
 * All state runs on [dispatch]'s thread, including the scheduled deadline. */
internal class AndroidAutoLoadCoordinator(
  private val contextKey: () -> String?,
  private val now: () -> Long,
  private val dispatch: (() -> Unit) -> Unit,
  private val schedule: (Long, () -> Unit) -> (() -> Unit),
  private val log: (String) -> Unit,
  private val timeoutMs: Long = 15_000L
) {
  private var sequence = 0L
  private var active: Load? = null

  fun load(cb: (Boolean) -> Unit, start: (Load) -> Unit) {
    val previous = active
    if (previous != null && previous.key == contextKey() && previous.accept()) {
      previous.callbacks.add(cb)
      log("load=${previous.id} coalesced")
      return
    }
    invalidate()
    val load = Load(++sequence, contextKey(), now(), mutableListOf(cb))
    active = load
    log("load=${load.id} start")
    load.cancelDeadline = schedule(timeoutMs) { load.finish(false, "timeout") }
    load.guard { start(load) }
  }

  fun invalidate() { active?.finish(false, "invalidated") }

  inner class Load internal constructor(
    val id: Long,
    internal var key: String?,
    private val startedAt: Long,
    internal val callbacks: MutableList<(Boolean) -> Unit>
  ) {
    internal var cancelDeadline: (() -> Unit)? = null
    private var stage = "start"
    private var stageSequence = 0L

    internal fun accept(): Boolean {
      if (active !== this) return false
      if (key != contextKey()) { finish(false, "context_changed"); return false }
      if (now() - startedAt >= timeoutMs) { finish(false, "timeout"); return false }
      return true
    }

    internal fun guard(action: () -> Unit) {
      if (!accept()) return
      try { action() } catch (error: Exception) {
        // Exception messages can contain URLs/credentials. Retain only the exception type.
        finish(false, "exception:${error.javaClass.simpleName}")
      }
    }

    /** Only the loader's own validated fallback selection may change its context. */
    fun adoptContext(change: () -> Unit) {
      if (!accept()) return
      change()
      key = contextKey()
    }

    fun <T> stage(name: String, request: ((T) -> Unit) -> Unit, receive: (T) -> Unit) {
      guard {
        stage = name
        val step = ++stageSequence
        val stageStartedAt = now()
        log("load=$id stage=$stage start elapsedMs=${now() - startedAt}")
        request { value ->
          dispatch {
            if (active !== this) {
              log("load=$id stage=$name ignored=expired")
              return@dispatch
            }
            guard {
              if (step == stageSequence) {
                ++stageSequence // A duplicate callback cannot run this stage twice.
                log("load=$id stage=$name complete durationMs=${now() - stageStartedAt}")
                receive(value)
              }
            }
          }
        }
      }
    }

    fun complete(success: Boolean, reason: String) {
      if (accept()) finish(success, reason)
    }

    fun diagnostic(message: String) {
      if (accept()) log("load=$id stage=$stage $message elapsedMs=${now() - startedAt}")
    }

    internal fun finish(success: Boolean, reason: String) {
      if (active !== this) return
      active = null
      cancelDeadline?.invoke()
      log("load=$id stage=$stage outcome=$reason success=$success elapsedMs=${now() - startedAt}")
      val pending = callbacks.toList()
      callbacks.clear()
      pending.forEach { cb ->
        try { cb(success) } catch (error: Exception) {
          log("load=$id callback_exception:${error.javaClass.simpleName}")
        }
      }
    }
  }
}
