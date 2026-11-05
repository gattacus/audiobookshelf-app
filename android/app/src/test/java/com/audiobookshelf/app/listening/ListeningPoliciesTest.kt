package com.audiobookshelf.app.listening

import com.audiobookshelf.app.player.PodcastPlaybackPolicy
import org.junit.Assert.*
import org.junit.Test

class ListeningPoliciesTest {
  private val identity = ListeningIdentity("account", "user", "book")
  private fun episode(id: String, date: Long?, finished: Boolean = false, available: Boolean = true, index: Int = 0) =
    PodcastPlaybackPolicy.Episode(id, date, index, finished, available)
  @Test fun identitiesSeparateAccountsUsersAndAmbiguousDelimiters() {
    assertEquals(64, identity.copy(configId = "long/".repeat(100)).key.length)
    assertNotEquals(identity.key, identity.copy(userId = "other").key)
    assertNotEquals(identity.key, identity.copy(configId = "other").key)
    assertNotEquals(ListeningIdentity("a:b", "c", "d").key, ListeningIdentity("a", "b:c", "d").key)
    assertNotEquals(identity.key, identity.copy(episodeId = "episode").key)
  }
  @Test fun legacyStatusNeverAssumesAcknowledgement() {
    assertEquals("unverified", ProgressReceipt(identity).state(0))
    assertEquals("waiting", ProgressReceipt(identity).saved(100, 12.0, false, 200, "Book").state(0))
    assertEquals("local_only", ProgressReceipt(identity.copy(configId = "")).state(0))
  }
  @Test fun oldAcknowledgementCannotMarkNewPositionSynced() {
    val first = ProgressReceipt(identity).saved(1, 10.0, false, 1, "Book")
    val second = first.saved(2, 20.0, false, 2, "Book")
    val oldAck = second.acknowledged(first.revision, 10.0, false, 3)
    assertEquals("waiting", oldAck.state(0))
    val latest = oldAck.acknowledged(second.revision, 20.0, false, 4)
    assertEquals("synced", latest.state(0))
    assertEquals("waiting", latest.state(1))
    assertEquals(latest, latest.acknowledged(first.revision, 10.0, false, 5))
    assertEquals(latest, latest.acknowledged(-1, 99.0, false, 6))
  }
  @Test fun finishedToggleAndSameTimestampSeekInvalidateAcknowledgement() {
    val first = ProgressReceipt(identity).saved(10, 20.0, false, 10, "Book")
    val ack = first.acknowledged(first.revision, 20.0, false, 11)
    val changed = ack.saved(10, 20.0, true, 12, "Book")
    assertEquals("waiting", changed.state(0))
    assertTrue(changed.revision > ack.revision)
    assertEquals("waiting", ack.saved(10, 5.0, false, 12, "Book").state(0))
    assertEquals("synced", ack.saved(10, 20.0, false, 12, "Book").state(0))
  }
  @Test fun saveErrorTakesPrecedenceOverSynced() {
    val p = ProgressReceipt(identity).saved(1, 1.0, false, 1, "Book")
    assertEquals("error", p.acknowledged(p.revision, 1.0, false, 2).copy(error = "Disk full").state(0))
  }
  @Test fun undoGroupsSkipBurstsButNotChapterOrTimelineJumps() {
    val undo = PlaybackUndo()
    undo.record("book", 100, 110, "skip", 0)
    val burst = undo.record("book", 110, 120, "media_skip", 1999)!!
    assertEquals(100, burst.from)
    assertEquals(100L, undo.consume("book", burst.generation))
    assertNull(undo.consume("book", burst.generation))
    undo.record("book", 10, 20, "timeline", 3000)
    val chapter = undo.record("book", 20, 50, "chapter", 3001)!!
    assertEquals(20, chapter.from)
    val later = undo.record("book", 50, 60, "skip", 6000)!!
    assertEquals(50, later.from)
  }
  @Test fun staleUndoCannotChangeAnotherItemOrNewerJump() {
    val undo = PlaybackUndo()
    val old = undo.record("book", 100, 200, "timeline", 1)!!
    val latest = undo.record("book", 200, 300, "timeline", 2)!!
    assertNull(undo.consume("book", old.generation))
    assertNull(undo.consume("episode", latest.generation))
    undo.select("episode")
    assertNull(undo.jump)
  }
  @Test fun undoIgnoresNoopAndNegativePositions() {
    val undo = PlaybackUndo()
    assertNull(undo.record("book", 0, 0, "skip", 1))
    assertNull(undo.record("book", -1, 10, "timeline", 2))
  }
  @Test fun serialTargetsIncludeUnfinishedAnchorAndExcludeFinishedUnavailableDuplicates() {
    val episodes = listOf(episode("third",3), episode("first",1), episode("second",2), episode("second",2), episode("finished",4,true), episode("missing",5,available=false), episode("sixth",6))
    assertEquals(listOf("second","third","sixth"), NextDownloadPolicy.targets(episodes,"second",true,3))
    assertEquals(listOf("first","second"), NextDownloadPolicy.targets(episodes,null,true,2))
    assertEquals(emptyList<String>(), NextDownloadPolicy.targets(episodes,"deleted",true,3))
  }
  @Test fun episodicOrderDoesNotWrapAndPreservesMissingDateTieOrder() {
    val episodes = listOf(episode("a",1), episode("b",2,true), episode("c",3), episode("undated",null))
    assertEquals(listOf("c","a","undated"),NextDownloadPolicy.targets(episodes,null,false,3))
    assertEquals(listOf("a","undated"),NextDownloadPolicy.targets(episodes,"b",false,3))
    assertEquals(listOf("undated"),NextDownloadPolicy.targets(episodes,"undated",false,3))
    assertEquals(listOf("z","a"),NextDownloadPolicy.targets(listOf(episode("z",null),episode("a",null)),null,true,3))
  }
  @Test fun automaticBudgetIncludesQueuedReservationsAndNeverOverflows() {
    assertTrue(AutomaticStorageBudget.canReserve(2*GIB,GIB,GIB/2,GIB/2))
    assertFalse(AutomaticStorageBudget.canReserve(2*GIB,GIB,GIB/2,GIB/2+1))
    assertFalse(AutomaticStorageBudget.canReserve(2*GIB,3*GIB,0,1))
    assertFalse(AutomaticStorageBudget.canReserve(Long.MAX_VALUE,Long.MAX_VALUE,1,1))
    assertTrue(AutomaticStorageBudget.canReserve(0,3*GIB,GIB,GIB))
  }
  private fun cleanup(automatic:Boolean=true, confirmed:Boolean=true, pending:Int=0, active:Boolean=false,
    protected:Boolean=false, transferring:Boolean=false, verified:Boolean=true, finished:Boolean=true, now:Long=25*3600000L):String? =
    FinishedCleanupPolicy.reason(finished,protected,active,transferring,verified,automatic,confirmed,pending,1,now,24)
  @Test fun automaticCleanupRequiresConfirmedFinishedRevisionAndNoPendingSessions() {
    assertNull(cleanup())
    assertNotNull(cleanup(confirmed=false))
    assertNotNull(cleanup(pending=1))
    assertNotNull(cleanup(now=3600000))
    assertNotNull(cleanup(finished=false))
  }
  @Test fun manualOfflineCleanupStillRespectsOwnershipPlaybackAndProtection() {
    assertNull(cleanup(automatic=false,confirmed=false,pending=3,now=2))
    assertNotNull(cleanup(automatic=false,verified=false))
    assertNotNull(cleanup(automatic=false,active=true))
    assertNotNull(cleanup(automatic=false,protected=true))
    assertNotNull(cleanup(automatic=false,transferring=true))
  }
}
