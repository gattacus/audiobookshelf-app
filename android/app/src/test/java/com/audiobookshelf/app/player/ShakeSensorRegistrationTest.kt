package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeSensorRegistrationTest {
  private class Cleanup(val run: () -> Unit, var cancelled: Boolean = false)

  private class Fixture {
    var registrations = 0
    var removals = 0
    var registrationSucceeds = true
    val cleanups = mutableListOf<Cleanup>()
    val sensor = ShakeSensorRegistration(
      registerListener = { registrations++; registrationSucceeds },
      unregisterListener = { removals++ },
      scheduleCleanup = { callback ->
        val cleanup = Cleanup(callback)
        cleanups.add(cleanup)
        val cancel = { cleanup.cancelled = true }
        cancel
      }
    )
  }

  @Test fun resetDuringGracePeriodCancelsCleanupWithoutRegisteringTwice() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod() // timer expires
    f.sensor.register() // shake restarts the timer during its grace period

    assertTrue(f.cleanups.single().cancelled)
    assertEquals(1, f.registrations)
    assertTrue(f.sensor.isRegistered)
    assertEquals(0, f.removals)
  }

  @Test fun alreadyDispatchedCleanupCannotDisableResetTimer() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    val dispatched = f.cleanups.single().run
    f.sensor.register()
    dispatched() // model a callback that cancellation cannot retract

    assertTrue(f.sensor.isRegistered)
    assertEquals(0, f.removals)
  }

  @Test fun resetTimerRetainsItsOwnGracePeriodAfterOldCallbackRuns() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    val first = f.cleanups.single()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod() // reset timer expires later
    first.run()
    assertTrue(f.sensor.isRegistered)
    f.cleanups.last().run()
    assertFalse(f.sensor.isRegistered)
    assertEquals(1, f.removals)
  }

  @Test fun ordinaryExpiryUnregistersOnceAndNextTimerRegistersAgain() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    assertTrue(f.sensor.isRegistered)
    val cleanup = f.cleanups.single().run
    cleanup()
    cleanup()
    assertFalse(f.sensor.isRegistered)
    assertEquals(1, f.removals)
    f.sensor.register()
    assertTrue(f.sensor.isRegistered)
    assertEquals(2, f.registrations)
  }

  @Test fun repeatedExpiryUsesOnlyLatestCleanup() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    f.sensor.unregisterAfterGracePeriod()
    assertTrue(f.cleanups.first().cancelled)
    f.cleanups.first().run()
    assertTrue(f.sensor.isRegistered)
    f.cleanups.last().run()
    assertFalse(f.sensor.isRegistered)
    assertEquals(1, f.removals)
  }

  @Test fun failedRegistrationCanBeRetriedAndDoesNotScheduleRemoval() {
    val f = Fixture()
    f.registrationSucceeds = false
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    assertFalse(f.sensor.isRegistered)
    assertTrue(f.cleanups.isEmpty())
    f.registrationSucceeds = true
    f.sensor.register()
    assertTrue(f.sensor.isRegistered)
    assertEquals(2, f.registrations)
  }

  @Test fun immediateRemovalInvalidatesCleanupAndAllowsFreshRegistration() {
    val f = Fixture()
    f.sensor.register()
    f.sensor.unregisterAfterGracePeriod()
    f.sensor.unregisterNow()
    assertTrue(f.cleanups.single().cancelled)
    assertFalse(f.sensor.isRegistered)
    f.sensor.register()
    f.cleanups.single().run()
    assertTrue(f.sensor.isRegistered)
    assertEquals(1, f.removals)
  }
}
