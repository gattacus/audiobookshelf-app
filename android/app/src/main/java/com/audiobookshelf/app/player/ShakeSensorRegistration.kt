package com.audiobookshelf.app.player

/** Owns shake-listener registration and its grace-period cleanup. Call only on the main thread. */
internal class ShakeSensorRegistration(
  private val registerListener: () -> Boolean,
  private val unregisterListener: () -> Unit,
  private val scheduleCleanup: (() -> Unit) -> (() -> Unit)
) {
  var isRegistered = false
    private set
  private var generation = 0L
  private var cancelCleanup: (() -> Unit)? = null

  fun register() {
    // A grace-period reset must cancel cleanup even when the listener is still registered.
    invalidateCleanup()
    if (!isRegistered) isRegistered = registerListener()
  }

  fun unregisterAfterGracePeriod() {
    invalidateCleanup()
    if (!isRegistered) return
    val cleanupGeneration = generation
    cancelCleanup = scheduleCleanup {
      // Cancellation alone cannot stop a callback that has already been dispatched.
      if (cleanupGeneration == generation) {
        cancelCleanup = null
        unregisterNow()
      }
    }
  }

  fun unregisterNow() {
    invalidateCleanup()
    if (isRegistered) {
      unregisterListener()
      isRegistered = false
    }
  }

  private fun invalidateCleanup() {
    generation++
    cancelCleanup?.invoke()
    cancelCleanup = null
  }
}
