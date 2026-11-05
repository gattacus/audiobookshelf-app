package com.audiobookshelf.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.player.PlayerListener
import com.audiobookshelf.app.player.PlayerNotificationService
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.util.FlagSet
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Listener-only fixtures: no service is started and no user database or preferences are opened. */
@RunWith(AndroidJUnit4::class)
class WidgetFeedbackDeviceTest {
  @Test
  fun autoRewindBufferingDoesNotLeaveWidgetStartingWhenLazyCallbackIsSkipped() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { exerciseAutoRewindEvents() }
  }

  private fun exerciseAutoRewindEvents() {
    val originalLazy = PlayerListener.lazyIsPlaying
    val originalPauseTime = PlayerListener.lastPauseTime
    var playing = false
    var playbackState = Player.STATE_BUFFERING
    var feedback = WidgetFeedback.STARTING
    val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
      when (method.name) {
        "isPlaying" -> playing
        "getPlaybackState" -> playbackState
        "getDuration", "getCurrentPosition" -> 0L
        "equals" -> proxy === args?.get(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "WidgetFeedbackTestPlayer"
        else -> error("Unexpected player call: ${method.name}")
      }
    } as Player
    val service = PlayerNotificationService().apply { currentPlayer = player }
    val listener = PlayerListener(service) {
      feedback = WidgetFeedbackPolicy.whenPlaybackChanges(feedback, true, player.isPlaying)
    }
    val events = Player.Events(FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED)
      .add(Player.EVENT_PLAYBACK_STATE_CHANGED).build())
    try {
      // Auto-rewind has already set lazy=true, then seeking temporarily starts buffering.
      PlayerListener.lazyIsPlaying = true
      listener.onIsPlayingChanged(false)
      listener.onEvents(player, events)
      assertEquals(WidgetFeedback.STARTING, feedback)

      playing = true
      playbackState = Player.STATE_READY
      listener.onIsPlayingChanged(true) // skipped by the lazy playback guard
      listener.onEvents(player, events)
      assertEquals(WidgetFeedback.PLAYING, feedback)

      // The same guard can skip a stopped/paused callback; final events still clear the badge.
      PlayerListener.lazyIsPlaying = false
      playing = false
      listener.onIsPlayingChanged(false)
      listener.onEvents(player, events)
      assertEquals(WidgetFeedback.IDLE, feedback)
    } finally {
      PlayerListener.lazyIsPlaying = originalLazy
      PlayerListener.lastPauseTime = originalPauseTime
    }
  }
}
