package com.audiobookshelf.app.server

import java.security.MessageDigest

/** A persisted retry belongs to one credential, never to a later login for the same saved server. */
internal class TokenRefreshRetry(
  private val currentFingerprint: () -> String?,
  private val refresh: (complete: (Outcome) -> Unit) -> Unit
) {
  enum class Outcome { SUCCESS, REJECTED, TRANSIENT }

  /** true requests another scheduled attempt; false finishes without modifying the saved session. */
  fun run(expectedFingerprint: String, attempt: Int, complete: (Boolean) -> Unit) {
    if (attempt >= MAX_ATTEMPTS || !matches(expectedFingerprint)) {
      complete(false)
      return
    }
    refresh { outcome ->
      complete(outcome == Outcome.TRANSIENT && attempt + 1 < MAX_ATTEMPTS && matches(expectedFingerprint))
    }
  }

  private fun matches(expected: String) = expected.isNotEmpty() && currentFingerprint() == expected

  companion object {
    // Attempts at approximately 0, 10, 30, 70, 150, 310, 630 and 1270 seconds while connected.
    const val MAX_ATTEMPTS = 8
    const val BACKOFF_SECONDS = 10L

    /** WorkManager persists only this digest, not an additional copy of the refresh credential. */
    fun fingerprint(token: String?): String? = token?.takeIf { it.isNotBlank() }?.let {
      MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
  }
}
