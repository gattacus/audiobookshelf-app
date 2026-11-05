package com.audiobookshelf.app.listening

import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import io.paperdb.Paper
import java.util.concurrent.CopyOnWriteArraySet

object ListeningFileAccess { val lock = Any() }

/** Account identity is part of every key; server IDs alone are not globally unique. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class ListeningIdentity(val configId: String, val userId: String, val itemId: String, val episodeId: String? = null) {
  @get:com.fasterxml.jackson.annotation.JsonIgnore
  val key: String get() = ListeningStorageKeys.compact(rawKey)
  @get:com.fasterxml.jackson.annotation.JsonIgnore
  val rawKey: String get() = listOf(configId, userId, itemId, episodeId.orEmpty()).joinToString(":") { "${it.length}:$it" }
  @get:com.fasterxml.jackson.annotation.JsonIgnore
  val serverLinked: Boolean get() = configId.isNotBlank() && userId.isNotBlank()
  companion object {
    fun from(p: LocalMediaProgress) = ListeningIdentity(p.serverConnectionConfigId.orEmpty(), p.serverUserId.orEmpty(), p.libraryItemId ?: p.localLibraryItemId, p.episodeId ?: p.localEpisodeId)
    fun from(p: PlaybackSession) = ListeningIdentity(p.serverConnectionConfigId.orEmpty(), p.userId.orEmpty(), p.libraryItemId ?: p.localLibraryItem?.id.orEmpty(), p.episodeId ?: p.localEpisodeId)
  }
}

data class ProgressReceipt(
  val identity: ListeningIdentity,
  val title: String = "",
  val revision: Long = 0,
  val updatedAt: Long = 0,
  val position: Double = 0.0,
  val finished: Boolean = false,
  val savedAt: Long = 0,
  val acknowledgedRevision: Long = 0,
  val acknowledgedAt: Long = 0,
  val serverPosition: Double? = null,
  val serverFinished: Boolean? = null,
  val error: String? = null
) {
  fun saved(time: Long, position: Double, finished: Boolean, now: Long, title: String): ProgressReceipt {
    val changed = time != updatedAt || position != this.position || finished != this.finished || revision == 0L
    return copy(title = title.ifBlank { this.title }, revision = revision + if (changed) 1 else 0,
      updatedAt = time, position = position, finished = finished, savedAt = now, error = null)
  }
  fun acknowledged(sentRevision: Long, position: Double, finished: Boolean, now: Long): ProgressReceipt =
    if (sentRevision <= 0 || sentRevision < acknowledgedRevision || sentRevision > revision) this
    else copy(acknowledgedRevision = sentRevision, acknowledgedAt = now, serverPosition = position,
      serverFinished = finished, error = if (sentRevision == revision) null else error)
  fun state(pending: Int, busy: Boolean = false): String = when {
    error != null -> "error"
    !identity.serverLinked -> "local_only"
    busy -> "syncing"
    savedAt == 0L -> "unverified"
    acknowledgedRevision == revision && pending == 0 -> "synced"
    else -> "waiting"
  }
}

/** Separate Paper book: no modifications to persisted playback/session class layouts. */
object ProgressStatusStore {
  val availabilityListeners = CopyOnWriteArraySet<() -> Unit>()
  fun availabilityChanged() { availabilityListeners.forEach { runCatching { it() } }; changed() }
  val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val transientErrors = mutableMapOf<String, String>()
  private val saving = mutableSetOf<String>()
  private val syncing = mutableMapOf<String, Long>()
  private fun emit() { listeners.forEach { runCatching { it() } } }
  @Synchronized fun get(id: ListeningIdentity): ProgressReceipt {
    ListeningStorageKeys.migrate("progressReceipts")
    return runCatching { Paper.book("progressReceipts").read<ProgressReceipt>(id.key) }.getOrNull()
      ?.takeIf { runCatching { it.identity == id && it.revision >= 0 && it.acknowledgedRevision in 0..it.revision && it.savedAt >= 0 && it.position.isFinite() && it.position >= 0 }.getOrDefault(false) } ?: ProgressReceipt(id)
  }
  @Synchronized private fun put(value: ProgressReceipt) { Paper.book("progressReceipts").write(value.identity.key, value) }
  fun persist(id: ListeningIdentity, updatedAt: Long, position: Double, finished: Boolean, title: String = "", write: () -> Unit) {
    synchronized(this) { saving.add(id.key) }; emit()
    try {
      write()
      synchronized(this) {
        put(get(id).saved(updatedAt, position, finished, System.currentTimeMillis(), title))
        transientErrors.remove(id.key)
      }
    } catch (e: Exception) {
      synchronized(this) { transientErrors[id.key] = "Could not save progress on this phone" }
      throw e
    } finally { synchronized(this) { saving.remove(id.key) }; emit() }
  }
  fun seed(p: LocalMediaProgress) {
    val id = ListeningIdentity.from(p)
    synchronized(this) {
      if (get(id).revision == 0L) put(get(id).saved(p.lastUpdate, p.currentTime, p.isFinished, p.lastUpdate, ""))
    }
  }
  @Synchronized fun token(id: ListeningIdentity): Long = get(id).revision
  fun beginSnapshot(id: ListeningIdentity, updatedAt: Long, position: Double, finished: Boolean): Long {
    val token = synchronized(this) {
      val p = get(id)
      if (p.savedAt > 0 && p.updatedAt == updatedAt && p.position == position && p.finished == finished) p.revision.also { syncing[id.key] = it } else -1L
    }
    emit(); return token
  }
  fun begin(id: ListeningIdentity): Long {
    val token = synchronized(this) { token(id).also { syncing[id.key] = it } }
    emit(); return token
  }
  fun complete(id: ListeningIdentity, token: Long, position: Double, finished: Boolean, success: Boolean, error: String? = null) {
    synchronized(this) {
      if (syncing[id.key] == token) syncing.remove(id.key)
      val current = get(id)
      put(if (success) current.acknowledged(token, position, finished, System.currentTimeMillis())
        else if (token == current.revision) current.copy(error = error ?: "Server sync failed; saved on this phone") else current)
    }
    emit()
    if (success && finished) com.audiobookshelf.app.managers.DbManager.applicationContext?.let { DownloadAutomation.schedule(it) }
  }
  fun pending(id: ListeningIdentity): Int = DeviceManager.dbManager.getPlaybackSessions().count { ListeningIdentity.from(it) == id }
  fun snapshot(id: ListeningIdentity): Map<String, Any?> {
    DeviceManager.dbManager.getAllLocalMediaProgress().find { ListeningIdentity.from(it) == id }?.let { seed(it) }
    val p = get(id)
    val pending = pending(id)
    return synchronized(this) { mapOf("identity" to id, "title" to p.title.ifBlank { DeviceManager.dbManager.getAllLocalMediaProgress().find { ListeningIdentity.from(it) == id }?.let { progress -> DeviceManager.dbManager.getLocalLibraryItem(progress.localLibraryItemId)?.let { local -> (local.media as? com.audiobookshelf.app.data.Podcast)?.episodes?.find { it.id == progress.localEpisodeId }?.title ?: local.title } } ?: "Listening item" }, "state" to
      (if (transientErrors.containsKey(id.key)) "error" else if (id.key in saving) "saving" else p.state(pending, id.key in syncing)),
      "savedAt" to p.savedAt, "updatedAt" to p.updatedAt, "position" to p.position, "acknowledgedAt" to p.acknowledgedAt,
      "serverPosition" to p.serverPosition, "pendingSessions" to pending, "error" to (transientErrors[id.key] ?: p.error)) }
  }
  fun identities(): List<ListeningIdentity> = (DeviceManager.dbManager.getAllLocalMediaProgress().map { ListeningIdentity.from(it) } +
    DeviceManager.dbManager.getPlaybackSessions().map { ListeningIdentity.from(it) } +
    Paper.book("progressReceipts").allKeys.mapNotNull { key -> runCatching { Paper.book("progressReceipts").read<ProgressReceipt>(key)?.identity }.getOrNull() }).distinct()
  fun changed() = emit()
}
