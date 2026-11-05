package com.audiobookshelf.app.listening

import com.audiobookshelf.app.models.DownloadItem
import io.paperdb.Paper

/** Bookkeeping is independent of upgrade-sensitive media/queue records. */
object ManagedDownloadStore {
  private inline fun <reified T> all(book: String): List<T> {
    ListeningStorageKeys.migrate(book)
    return Paper.book(book).allKeys.mapNotNull { runCatching { Paper.book(book).read<T>(it) }.getOrNull() }
  }
  fun rules(): List<PodcastDownloadRule> = all<PodcastDownloadRule>("podcastDownloadRules").filter {
    runCatching { it.identity.serverLinked && it.identity.itemId.isNotBlank() && it.count in 1..100 && it.folderId.isNotBlank() }.getOrDefault(false)
  }
  fun rule(id: ListeningIdentity) = rules().find { it.identity == id } ?: PodcastDownloadRule(id)
  fun save(rule: PodcastDownloadRule) { Paper.book("podcastDownloadRules").write(rule.identity.key, rule); ProgressStatusStore.changed() }
  fun settings(): DownloadHousekeepingSettings = runCatching { Paper.book("downloadHousekeeping").read<DownloadHousekeepingSettings>("settings") }.getOrNull()
    ?.takeIf { runCatching { it.automaticLimitBytes in listOf(0L, GIB, 2*GIB, 5*GIB, 10*GIB) && it.bookDelayHours in listOf(0,24,168) && it.episodeDelayHours in listOf(0,24,168) }.getOrDefault(false) } ?: DownloadHousekeepingSettings()
  fun save(settings: DownloadHousekeepingSettings) { Paper.book("downloadHousekeeping").write("settings", settings); ProgressStatusStore.changed() }
  fun audio(): List<OwnedAudio> = all<OwnedAudio>("ownedAudio").filter {
    runCatching { it.identity.serverLinked && it.localItemId.isNotBlank() && it.fileId.isNotBlank() && it.contentUrl.isNotBlank() && it.path.isNotBlank() && it.bytes >= 0 }.getOrDefault(false)
  }
  @Synchronized fun save(file: OwnedAudio) { Paper.book("ownedAudio").write(file.key, file) }
  fun protected(id: ListeningIdentity): Boolean { ListeningStorageKeys.migrate("keepDownloaded"); return Paper.book("keepDownloaded").read<Boolean>(id.key) == true }
  fun protect(id: ListeningIdentity, enabled: Boolean) { synchronized(ListeningFileAccess.lock) { Paper.book("keepDownloaded").write(id.key, enabled) }; ProgressStatusStore.changed() }
  fun transfer(item: DownloadItem): AutomaticTransfer? { ListeningStorageKeys.migrate("automaticTransfers"); return runCatching { Paper.book("automaticTransfers").read<AutomaticTransfer>(ListeningStorageKeys.compact("${item.serverConnectionConfigId}:${item.id}")) }.getOrNull()
  }
  fun transfer(item: DownloadItem, value: AutomaticTransfer) { Paper.book("automaticTransfers").write(ListeningStorageKeys.compact("${item.serverConnectionConfigId}:${item.id}"), value) }
  fun finishTransfer(item: DownloadItem) { Paper.book("automaticTransfers").delete(ListeningStorageKeys.compact("${item.serverConnectionConfigId}:${item.id}")) }
  fun retains(itemId: String, episodeId: String? = null): Boolean = audio().any { it.localItemId == itemId && (episodeId == null || it.localEpisodeId == episodeId) && (it.removedAt != null || it.deleting) }
  fun firstFinished(id: ListeningIdentity, finished: Boolean, timestamp: Long?): Long {
    ListeningStorageKeys.migrate("cleanupFirstFinished")
    val book = Paper.book("cleanupFirstFinished")
    if (!finished) { book.delete(id.key); return 0 }
    val time = timestamp?.takeIf { it > 0 } ?: book.read<Long>(id.key) ?: System.currentTimeMillis()
    book.write(id.key, time)
    return time
  }
}
