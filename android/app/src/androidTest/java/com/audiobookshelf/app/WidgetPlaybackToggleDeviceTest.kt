package com.audiobookshelf.app

import android.appwidget.AppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.audiobookshelf.app.player.PlayerNotificationService
import com.google.android.exoplayer2.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Unstarted service and in-memory player: no user database, widget preferences or files are touched. */
@RunWith(AndroidJUnit4::class)
class WidgetPlaybackToggleDeviceTest {
  @Test fun serverBookPausesAndResumesItsCurrentSession() = exercise("book", false)
  @Test fun downloadedBookPausesAndResumesItsCurrentSession() = exercise("book", true)
  @Test fun serverPodcastPausesAndResumesItsCurrentEpisode() = exercise("podcast", false)
  @Test fun localPodcastPausesAndResumesItsCurrentEpisode() = exercise("podcast", true)

  private fun exercise(mediaType: String, local: Boolean) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val metadata = MediaTypeMetadata("Widget item", false)
      val download = if (local) LocalLibraryItem("download", "folder", "", "", "", false,
        mediaType, MediaType(metadata, null), mutableListOf(), null, null, true,
        null, null, null, null) else null
      val session = PlaybackSession(
        id = "session", userId = null, libraryItemId = if (local) null else "item",
        episodeId = if (mediaType == "podcast") "current-episode" else null,
        mediaType = mediaType, mediaMetadata = metadata,
        deviceInfo = DeviceInfo("test", "Test", "Test", 34, "test"), chapters = emptyList(),
        displayTitle = "Widget item", displayAuthor = null, coverPath = null, duration = 100.0,
        playMethod = if (local) PLAYMETHOD_LOCAL else PLAYMETHOD_DIRECTPLAY,
        startedAt = 0L, updatedAt = 0L, timeListening = 0L, audioTracks = mutableListOf(),
        currentTime = 42.0, libraryItem = null, localLibraryItem = download,
        localEpisodeId = if (local && mediaType == "podcast") "local-episode" else null,
        serverConnectionConfigId = if (local) null else "server", serverAddress = null,
        mediaPlayer = "exo-player"
      )
      val target = WidgetTarget("Widget item", mediaType, "episodic",
        session.libraryItemId, session.serverConnectionConfigId, download?.id)
      var playing = true
      var pauses = 0
      var resumes = 0
      var feedback = WidgetFeedback.PLAYING
      val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
          "isPlaying" -> playing
          "getMediaItemCount" -> 1
          "getPlaybackState" -> Player.STATE_READY
          "pause" -> { pauses++; playing = false; null }
          "play" -> { resumes++; playing = true; null }
          "setVolume" -> null
          else -> error("Unexpected player call: ${method.name}")
        }
      } as Player
      val service = PlayerNotificationService().apply {
        currentPlayer = player
        currentPlaybackSession = session
      }
      val widget = WidgetPlayback(service) {
        feedback = WidgetFeedbackPolicy.whenPlaybackChanges(feedback, true, player.isPlaying)
      }

      repeat(2) {
        widget.play(target, AppWidgetManager.INVALID_APPWIDGET_ID)
        assertFalse(player.isPlaying)
        assertEquals(WidgetFeedback.IDLE, feedback)

        widget.play(target, AppWidgetManager.INVALID_APPWIDGET_ID)
        assertTrue(player.isPlaying)
        assertEquals(WidgetFeedback.PLAYING, feedback)
        assertSame(session, service.currentPlaybackSession)
        assertEquals(42.0, session.currentTime, 0.0)
        assertEquals(if (mediaType == "podcast") "current-episode" else null, session.episodeId)
      }
      assertEquals(2, pauses)
      assertEquals(2, resumes)
    }
  }
}
