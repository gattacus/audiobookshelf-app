package com.audiobookshelf.app.server

import org.junit.Assert.*
import org.junit.Test

class TokenRefreshRetryTest {
  private class Fixture {
    var credential: String? = "original-refresh"
    val fingerprint = TokenRefreshRetry.fingerprint(credential)!!
    var requests = 0
    var pending: ((TokenRefreshRetry.Outcome) -> Unit)? = null
    var again: Boolean? = null

    // Creating a fresh runner models a new worker/process reading the persisted task input.
    fun run(attempt: Int = 0) {
      again = null
      TokenRefreshRetry(
        { TokenRefreshRetry.fingerprint(credential) },
        { requests++; pending = it }
      ).run(fingerprint, attempt) { again = it }
    }

    fun respond(outcome: TokenRefreshRetry.Outcome) { pending!!(outcome); pending = null }
  }

  @Test fun interruptedRenewalRetriesIndependentlyThenStoresReplacementAndStops() {
    val f = Fixture()
    f.run()
    f.respond(TokenRefreshRetry.Outcome.TRANSIENT)
    assertEquals(true, f.again)
    assertEquals("original-refresh", f.credential)

    // No playback/progress/API trigger is needed for the scheduled second attempt.
    f.run(1)
    f.credential = "replacement-refresh"
    f.respond(TokenRefreshRetry.Outcome.SUCCESS)
    assertEquals(false, f.again)
    assertEquals(2, f.requests)
    assertEquals("replacement-refresh", f.credential)
  }

  @Test fun pendingWorkDoesNotRenewOrInvalidateANewerLogin() {
    val f = Fixture()
    f.credential = "new-login-refresh"
    f.run()
    assertEquals(0, f.requests)
    assertEquals(false, f.again)
    assertEquals("new-login-refresh", f.credential)
  }

  @Test fun clearingCredentialBeforeNetworkReturnsFinishesWithoutARequest() {
    val f = Fixture()
    f.credential = null
    f.run()
    assertEquals(0, f.requests)
    assertEquals(false, f.again)
  }

  @Test fun nativeOrFrontendRenewalWhileRetryIsInFlightStopsFurtherRetries() {
    val f = Fixture()
    f.run()
    f.credential = "other-renewal-refresh"
    f.respond(TokenRefreshRetry.Outcome.TRANSIENT)
    assertEquals(false, f.again)
    assertEquals("other-renewal-refresh", f.credential)
  }

  @Test fun rejectionDoesNotLoopOrRequireRestoringAnInvalidCredential() {
    val f = Fixture()
    f.run()
    f.credential = null // The HTTP owner handles explicit rejection.
    f.respond(TokenRefreshRetry.Outcome.REJECTED)
    assertEquals(false, f.again)
    assertNull(f.credential)
  }

  @Test fun successWithoutRotationStillFinishes() {
    val f = Fixture()
    f.run()
    f.respond(TokenRefreshRetry.Outcome.SUCCESS)
    assertEquals(false, f.again)
  }

  @Test fun retryBudgetSurvivesNewRunnerInstancesAndPreservesCredentialsOnExhaustion() {
    val f = Fixture()
    repeat(TokenRefreshRetry.MAX_ATTEMPTS) { attempt ->
      f.run(attempt)
      f.respond(TokenRefreshRetry.Outcome.TRANSIENT)
      assertEquals(attempt + 1 < TokenRefreshRetry.MAX_ATTEMPTS, f.again)
    }
    f.run(TokenRefreshRetry.MAX_ATTEMPTS)
    assertEquals(false, f.again)
    assertEquals(TokenRefreshRetry.MAX_ATTEMPTS, f.requests)
    assertEquals("original-refresh", f.credential)
  }

  @Test fun persistedFingerprintContainsNoCredentialAndRejectsMissingCredentials() {
    assertNull(TokenRefreshRetry.fingerprint(null))
    assertNull(TokenRefreshRetry.fingerprint(""))
    assertNull(TokenRefreshRetry.fingerprint("  "))
    val digest = TokenRefreshRetry.fingerprint("synthetic-refresh")!!
    assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
    assertEquals(digest, TokenRefreshRetry.fingerprint("synthetic-refresh"))
    assertNotEquals(digest, TokenRefreshRetry.fingerprint("new-synthetic-refresh"))
    var requested = false
    TokenRefreshRetry({ null }, { requested = true }).run("", 0) { assertFalse(it) }
    assertFalse(requested)
  }
}
