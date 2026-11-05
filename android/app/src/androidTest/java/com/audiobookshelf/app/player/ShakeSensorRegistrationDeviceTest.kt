package com.audiobookshelf.app.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real sensor and service wiring, without starting playback or opening the user's database. */
@RunWith(AndroidJUnit4::class)
class ShakeSensorRegistrationDeviceTest {
  @Test fun gracePeriodResetSurvivesOldDeadlineWhileOrdinaryExpiryStillUnregisters() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accelerometer = requireNotNull(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abs:shake-regression")
    val deadline = CountDownLatch(1)
    val handler = Handler(Looper.getMainLooper())
    val signal = Runnable { deadline.countDown() }
    var reset: PlayerNotificationService? = null
    var ordinary: PlayerNotificationService? = null

    fun registration(service: PlayerNotificationService): ShakeSensorRegistration {
      return PlayerNotificationService::class.java.getDeclaredField("shakeSensorRegistration")
        .apply { isAccessible = true }.get(service) as ShakeSensorRegistration
    }

    fun service(): PlayerNotificationService {
      val service = PlayerNotificationService()
      mapOf("mSensorManager" to manager, "mAccelerometer" to accelerometer,
        "mShakeDetector" to ShakeDetector()).forEach { (name, value) ->
        PlayerNotificationService::class.java.getDeclaredField(name)
          .apply { isAccessible = true }.set(service, value)
      }
      return service
    }

    wakeLock.acquire(SLEEP_TIMER_WAKE_UP_EXPIRATION + 15000L)
    try {
      instrumentation.runOnMainSync {
        reset = service()
        ordinary = service()
        reset!!.registerSensor()
        ordinary!!.registerSensor()
        assertTrue(registration(reset!!).isRegistered)
        assertTrue(registration(ordinary!!).isRegistered)
        reset!!.unregisterSensor()
        ordinary!!.unregisterSensor()
        // Same path as a shake restarting a timer before the grace period ends.
        reset!!.registerSensor()
        handler.postDelayed(signal, SLEEP_TIMER_WAKE_UP_EXPIRATION + 500L)
      }
      assertTrue("Main-thread grace-period deadline was not reached",
        deadline.await(SLEEP_TIMER_WAKE_UP_EXPIRATION + 10000L, TimeUnit.MILLISECONDS))
      instrumentation.runOnMainSync {
        assertTrue("Old cleanup disabled the restarted timer's shake listener",
          registration(reset!!).isRegistered)
        assertFalse("Ordinary expiry should still release the sensor",
          registration(ordinary!!).isRegistered)
      }
    } finally {
      instrumentation.runOnMainSync {
        handler.removeCallbacks(signal)
        reset?.let { registration(it).unregisterNow() }
        ordinary?.let { registration(it).unregisterNow() }
      }
      if (wakeLock.isHeld) wakeLock.release()
    }
  }
}
