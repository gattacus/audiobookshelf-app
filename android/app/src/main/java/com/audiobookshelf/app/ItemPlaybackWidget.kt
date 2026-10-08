package com.audiobookshelf.app

import android.app.PendingIntent
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import org.json.JSONObject

internal data class WidgetTarget(
  val title: String,
  val mediaType: String,
  val podcastType: String,
  val serverItemId: String?,
  val serverConfigId: String?,
  val localItemId: String?,
  val episodeDates: String = "{}",
  val hasCover: Boolean = false
) {
  private val dates by lazy { try { JSONObject(episodeDates) } catch (_: Exception) { JSONObject() } }

  fun toJson() = JSONObject().put("title", title).put("mediaType", mediaType)
    .put("podcastType", podcastType).put("serverItemId", serverItemId)
    .put("serverConfigId", serverConfigId).put("localItemId", localItemId)
    .put("episodeDates", episodeDates).put("hasCover", hasCover).toString()

  fun dateFor(episode: com.audiobookshelf.app.data.PodcastEpisode): Long? {
    val key = episode.serverEpisodeId ?: episode.id
    return episode.publishedAt ?: dates.optLong(key).takeIf { it > 0 }
  }

  companion object {
    fun fromJson(json: String): WidgetTarget? = try {
      val data = JSONObject(json)
      WidgetTarget(data.getString("title"), data.getString("mediaType"), data.optString("podcastType"),
        data.optString("serverItemId").takeIf { it.isNotBlank() && it != "null" },
        data.optString("serverConfigId").takeIf { it.isNotBlank() && it != "null" },
        data.optString("localItemId").takeIf { it.isNotBlank() && it != "null" },
        data.optString("episodeDates", "{}"), data.optBoolean("hasCover", false))
    } catch (error: Exception) { null }
  }
}

internal object WidgetTargetStore {
  private const val PREFS = "item_playback_widgets"
  fun get(context: Context, id: Int): WidgetTarget? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    .getString(id.toString(), null)?.let(WidgetTarget::fromJson)
  fun put(context: Context, id: Int, target: WidgetTarget) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id.toString(), target.toJson()).apply()
  }
  fun remove(context: Context, id: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
  }
}

internal object WidgetFeedbackStore {
  private const val PREFS = "item_playback_widget_feedback"
  data class Saved(val state: WidgetFeedback, val at: Long)

  fun get(context: Context, id: Int): Saved {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val state = runCatching { WidgetFeedback.valueOf(prefs.getString("state_$id", "IDLE") ?: "IDLE") }
      .getOrDefault(WidgetFeedback.IDLE)
    return Saved(state, prefs.getLong("at_$id", 0L))
  }

  fun set(context: Context, id: Int, state: WidgetFeedback, at: Long = SystemClock.elapsedRealtime()) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString("state_$id", state.name).putLong("at_$id", at).apply()
  }

  fun remove(context: Context, id: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .remove("state_$id").remove("at_$id").apply()
  }
}

class ItemPlaybackWidget : AppWidgetProvider() {
  companion object {
    const val ACTION_PINNED = "com.audiobookshelf.app.ITEM_WIDGET_PINNED"
    private const val ACTION_STATUS_TIMEOUT = "com.audiobookshelf.app.ITEM_WIDGET_STATUS_TIMEOUT"
    const val EXTRA_TARGET = "widgetTarget"
    private const val EXTRA_STATUS_AT = "statusAt"
    private const val START_TIMEOUT_MS = 30_000L
    private const val ERROR_DISPLAY_MS = 4_000L
    private const val PLAYING_CHECK_MS = 60_000L

    private fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
      .getAppWidgetIds(ComponentName(context, ItemPlaybackWidget::class.java))

    private fun applyFeedback(context: Context, views: RemoteViews, target: WidgetTarget?, state: WidgetFeedback) {
      val description = when (state) {
        WidgetFeedback.STARTING -> R.string.item_widget_starting
        WidgetFeedback.PLAYING -> R.string.item_widget_playing
        WidgetFeedback.ERROR -> R.string.item_widget_failed
        WidgetFeedback.IDLE -> R.string.item_widget_play
      }
      views.setContentDescription(R.id.itemWidgetBackground,
        target?.let { context.getString(description, it.title) } ?: context.getString(R.string.item_widget_unconfigured))
      views.setViewVisibility(R.id.itemWidgetPlayIcon, if (state == WidgetFeedback.IDLE) View.VISIBLE else View.GONE)
      views.setViewVisibility(R.id.itemWidgetSpinner, if (state == WidgetFeedback.STARTING) View.VISIBLE else View.GONE)
      views.setViewVisibility(R.id.itemWidgetPlayingIcon, if (state == WidgetFeedback.PLAYING) View.VISIBLE else View.GONE)
      views.setViewVisibility(R.id.itemWidgetErrorIcon, if (state == WidgetFeedback.ERROR) View.VISIBLE else View.GONE)
    }

    private fun timeoutIntent(context: Context, id: Int, at: Long): PendingIntent {
      val intent = Intent(context, ItemPlaybackWidget::class.java).apply {
        action = ACTION_STATUS_TIMEOUT
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        putExtra(EXTRA_STATUS_AT, at)
      }
      return PendingIntent.getBroadcast(context, id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleCheck(context: Context, id: Int, state: WidgetFeedback, at: Long) {
      val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      val pending = timeoutIntent(context, id, at)
      alarm.cancel(pending)
      val delay = when (state) {
        WidgetFeedback.STARTING -> START_TIMEOUT_MS
        WidgetFeedback.ERROR -> ERROR_DISPLAY_MS
        WidgetFeedback.PLAYING -> PLAYING_CHECK_MS
        WidgetFeedback.IDLE -> return
      }
      alarm.set(AlarmManager.ELAPSED_REALTIME, at + delay, pending)
    }

    private fun show(context: Context, id: Int, state: WidgetFeedback) {
      val target = WidgetTargetStore.get(context, id) ?: return
      val current = WidgetFeedbackStore.get(context, id)
      if (state == current.state && state != WidgetFeedback.STARTING) return
      val at = SystemClock.elapsedRealtime()
      WidgetFeedbackStore.set(context, id, state, at)
      val views = RemoteViews(context.packageName, R.layout.item_playback_widget)
      applyFeedback(context, views, target, state)
      AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, views)
      scheduleCheck(context, id, state, at)
    }

    fun showStarting(context: Context, id: Int) = show(context, id, WidgetFeedback.STARTING)
    fun showError(context: Context, id: Int) = show(context, id, WidgetFeedback.ERROR)

    fun syncPlayback(service: PlayerNotificationService) {
      val active = service.currentPlaybackSession
      val playing = active != null && service.currentPlayer.isPlaying
      widgetIds(service).forEach { id ->
        val target = WidgetTargetStore.get(service, id) ?: return@forEach
        val matches = active != null && WidgetTargetMatcher.matches(target, active.mediaType,
          active.libraryItemId, active.serverConnectionConfigId, active.localLibraryItem?.id)
        val current = WidgetFeedbackStore.get(service, id).state
        show(service, id, WidgetFeedbackPolicy.whenPlaybackChanges(current, matches, playing))
      }
    }

    fun clearPlayback(context: Context) {
      widgetIds(context).forEach { id ->
        if (WidgetFeedbackStore.get(context, id).state == WidgetFeedback.PLAYING) show(context, id, WidgetFeedback.IDLE)
      }
    }

    fun update(context: Context, id: Int) {
      val manager = AppWidgetManager.getInstance(context)
      val target = WidgetTargetStore.get(context, id)
      val views = RemoteViews(context.packageName, R.layout.item_playback_widget)
      val saved = WidgetFeedbackStore.get(context, id)
      val state = if (saved.state == WidgetFeedback.PLAYING && PlayerNotificationService.widgetInstance == null)
        WidgetFeedback.IDLE else saved.state
      if (state != saved.state) WidgetFeedbackStore.set(context, id, state)
      applyFeedback(context, views, target, state)
      views.setImageViewResource(R.id.itemWidgetCover, R.drawable.item_widget_placeholder)
      views.setTextViewText(R.id.itemWidgetTitle, target?.title ?: "")
      views.setViewVisibility(R.id.itemWidgetTitle, if (target == null) View.GONE else View.VISIBLE)
      val intent = if (target == null) Intent(context, MainActivity::class.java) else
        Intent(context, PlayerNotificationService::class.java).apply {
          action = PlayerNotificationService.ACTION_PLAY_WIDGET_TARGET
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      val pending = if (target == null) PendingIntent.getActivity(context, id, intent, flags)
        else PendingIntent.getForegroundService(context, id, intent, flags)
      views.setOnClickPendingIntent(R.id.itemWidgetBackground, pending)
      manager.updateAppWidget(id, views)

      if (target == null) return
      DbManager.initialize(context)
      val local = target.localItemId?.let { runCatching { DeviceManager.dbManager.getLocalLibraryItem(it) }.getOrNull() }
      val cover: Any? = if (!local?.coverContentUrl.isNullOrBlank()) local?.getCoverUri(context) else if (target.hasCover) {
        val config = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == target.serverConfigId }
        if (config != null && target.serverItemId != null) {
          GlideUrl("${config.address}/api/items/${target.serverItemId}/cover",
            LazyHeaders.Builder().addHeader("Authorization", "Bearer ${config.token}").build())
        } else null
      } else null
      if (cover != null) {
        val imageTarget = object : CustomTarget<Bitmap>(180, 180) {
          override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
            if (WidgetTargetStore.get(context, id) != target) return
            val coverViews = RemoteViews(context.packageName, R.layout.item_playback_widget)
            coverViews.setImageViewBitmap(R.id.itemWidgetCover, resource)
            coverViews.setViewVisibility(R.id.itemWidgetTitle, View.GONE)
            manager.partiallyUpdateAppWidget(id, coverViews)
          }
          override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        Glide.with(context.applicationContext).asBitmap().load(cover)
          .apply(RequestOptions().override(180, 180).error(R.drawable.item_widget_placeholder))
          .into(imageTarget)
      }
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_STATUS_TIMEOUT) {
      val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
      if (id == AppWidgetManager.INVALID_APPWIDGET_ID || WidgetTargetStore.get(context, id) == null) return
      val saved = WidgetFeedbackStore.get(context, id)
      if (saved.at != intent.getLongExtra(EXTRA_STATUS_AT, -1L)) return
      when (saved.state) {
        WidgetFeedback.STARTING, WidgetFeedback.ERROR ->
          show(context, id, WidgetFeedbackPolicy.whenTimedOut(saved.state))
        WidgetFeedback.PLAYING -> {
          val service = PlayerNotificationService.widgetInstance
          if (service == null) show(context, id, WidgetFeedback.IDLE)
          else {
            syncPlayback(service)
            if (WidgetFeedbackStore.get(context, id).state == WidgetFeedback.PLAYING) {
              val nextAt = SystemClock.elapsedRealtime()
              WidgetFeedbackStore.set(context, id, WidgetFeedback.PLAYING, nextAt)
              scheduleCheck(context, id, WidgetFeedback.PLAYING, nextAt)
            }
          }
        }
        WidgetFeedback.IDLE -> Unit
      }
      return
    }
    if (intent.action == ACTION_PINNED) {
      val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
      val target = intent.getStringExtra(EXTRA_TARGET)?.let(WidgetTarget::fromJson)
      if (id != AppWidgetManager.INVALID_APPWIDGET_ID && target != null) {
        WidgetTargetStore.put(context, id, target)
        update(context, id)
      } else Log.w("ItemPlaybackWidget", "Pin callback missing widget id or target")
    }
    super.onReceive(context, intent)
  }

  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { update(context, it) }
  }

  override fun onDeleted(context: Context, ids: IntArray) {
    ids.forEach {
      (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(timeoutIntent(context, it, 0L))
      WidgetFeedbackStore.remove(context, it)
      WidgetTargetStore.remove(context, it)
    }
  }
}
