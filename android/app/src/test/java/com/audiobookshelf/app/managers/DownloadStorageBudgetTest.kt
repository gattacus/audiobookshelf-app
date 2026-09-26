package com.audiobookshelf.app.managers

import org.junit.Assert.*
import org.junit.Test

class DownloadStorageBudgetTest {
  @Test fun phoneWithFiveGigabytesFreeCannotStartEpisodeAndReportsDeficit() {
    val space = DownloadStorageBudget.Space(4_997_087_232L, 117_332_594_688L)
    val result = DownloadStorageBudget.evaluate(space, 6_665_982L, 0L, true, 0L)
    assertEquals(DownloadStorageBudget.Result.INSUFFICIENT, result.result)
    assertEquals(876_208_484L, result.requiredFreeBytes - space.availableBytes)
  }

  @Test fun otherTransfersReservationsWaitInsteadOfFailing() {
    val space = DownloadStorageBudget.Space(200_000_000L, 1_000_000_000L)
    val result = DownloadStorageBudget.evaluate(space, 20_000_000L, 0L, true, 90_000_000L)
    assertEquals(DownloadStorageBudget.Result.RESERVED, result.result)
    assertEquals(DownloadStorageBudget.Result.AVAILABLE,
            DownloadStorageBudget.evaluate(space, 20_000_000L, 0L, true, 0L).result)
  }

  @Test fun sharedStorageIncludesBothStagingAndCopyWhileResumeCountsExistingBytes() {
    val space = DownloadStorageBudget.Space(140_000_000L, 1_000_000_000L)
    assertEquals(DownloadStorageBudget.Result.AVAILABLE,
            DownloadStorageBudget.evaluate(space, 30_000_000L, 10_000_000L, true, 0L).result)
    assertEquals(DownloadStorageBudget.Result.INSUFFICIENT,
            DownloadStorageBudget.evaluate(space, 30_000_000L, 10_000_000L, false, 0L).result)
  }

  @Test fun unknownLengthStillRequiresSafeStagingBudget() {
    val space = DownloadStorageBudget.Space(150_000_000L, 1_000_000_000L)
    assertEquals(DownloadStorageBudget.Result.INSUFFICIENT,
            DownloadStorageBudget.evaluate(space, 0L, 0L, true, 0L).result)
  }
}
