package com.audiobookshelf.app.server

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.plugins.AbsLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Native refresh recovery continues with the player in the background, or after process restart. */
class TokenRefreshRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val configId = inputData.getString(CONFIG) ?: return Result.success()
    val fingerprint = inputData.getString(CREDENTIAL) ?: return Result.success()
    DbManager.initialize(applicationContext)
    val storage = SecureStorage(applicationContext)
    return suspendCancellableCoroutine { continuation ->
      val retry = TokenRefreshRetry(
        currentFingerprint = {
          if (DeviceManager.getServerConnectionConfig(configId) == null) null
          else TokenRefreshRetry.fingerprint(storage.getRefreshToken(configId))
        },
        refresh = { complete ->
          AbsLogger.info(TAG, "Retrying interrupted token renewal (attempt ${runAttemptCount + 1})")
          val api = ApiHandler(applicationContext)
          api.refreshAuthTokens(configId, recoveryClient, retryTransientFailures = false) { result ->
            complete(when (result) {
              is ApiHandler.RefreshResult.Success -> TokenRefreshRetry.Outcome.SUCCESS
              ApiHandler.RefreshResult.Rejected -> TokenRefreshRetry.Outcome.REJECTED
              ApiHandler.RefreshResult.Transient -> TokenRefreshRetry.Outcome.TRANSIENT
            })
          }
        }
      )
      retry.run(fingerprint, runAttemptCount) { again ->
        if (continuation.isActive) continuation.resume(if (again) Result.retry() else Result.success())
      }
    }
  }

  companion object {
    private const val TAG = "TokenRefreshRetry"
    private const val CONFIG = "serverConfigId"
    private const val CREDENTIAL = "credentialFingerprint"
    private val recoveryClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    internal fun enqueue(context: Context, configId: String, refreshToken: String) {
      val fingerprint = TokenRefreshRetry.fingerprint(refreshToken) ?: return
      // Isolated instrumentation contexts must never enqueue work against the user's database.
      val app = context.applicationContext
      if (app !is android.app.Application) return
      val serverKey = TokenRefreshRetry.fingerprint(configId) ?: return
      val work = OneTimeWorkRequestBuilder<TokenRefreshRetryWorker>()
        .setInputData(workDataOf(CONFIG to configId, CREDENTIAL to fingerprint))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, TokenRefreshRetry.BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()
      // Repeated failures using the same credential must not reset backoff or create a retry storm.
      WorkManager.getInstance(app).enqueueUniqueWork("auth-refresh-$serverKey-$fingerprint", ExistingWorkPolicy.KEEP, work)
    }
  }
}
