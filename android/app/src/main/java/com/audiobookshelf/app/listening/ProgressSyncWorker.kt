package com.audiobookshelf.app.listening

import android.content.Context
import androidx.work.*
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Paused edits must sync even when neither a listening timer nor download automation is running. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    DbManager.initialize(applicationContext)
    val id = ListeningIdentity(inputData.getString("config").orEmpty(), inputData.getString("user").orEmpty(),
      inputData.getString("item").orEmpty(), inputData.getString("episode"))
    val selected = DownloadAutomation.selected()
    if (!id.serverLinked || selected?.id != id.configId || selected.userId != id.userId) return Result.success()
    return try {
      val ok = suspendCancellableCoroutine<Boolean> { continuation ->
        ApiHandler(applicationContext).syncLocalSessionsAndProgress(id) { success, _ -> if (continuation.isActive) continuation.resume(success) }
      }
      val receipt = ProgressStatusStore.get(id)
      if (ok && ProgressStatusStore.pending(id) == 0 && receipt.acknowledgedRevision == receipt.revision) Result.success() else Result.retry()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
  }
  companion object {
    fun enqueue(context: Context, id: ListeningIdentity) {
      if (!id.serverLinked || context.applicationContext !is android.app.Application) return
      val data = workDataOf("config" to id.configId, "user" to id.userId, "item" to id.itemId, "episode" to id.episodeId)
      WorkManager.getInstance(context).enqueueUniqueWork("listening-progress-${id.key}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ProgressSyncWorker>().setInputData(data)
          .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
  }
}
