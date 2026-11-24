package com.vyrk.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class BackupEngineTest {
    @Test
    fun decoderAndContainerFailuresTryTheBackupPlayerOncePerSource() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = false, backupEngine = true)
        assertEquals(RecoveryAction.TryBackupEngine, p.onFailure(ErrorClass.DECODER, canRefresh = true))
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.CONTAINER, canRefresh = true))
        // The next source gets its own try.
        assertEquals(RecoveryAction.TryBackupEngine, p.onFailure(ErrorClass.CONTAINER, canRefresh = true))
    }

    @Test
    fun aStalledDecoderCountsButALoadThatNeverFinishedDoesNot() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = false, backupEngine = true)
        assertEquals(RecoveryAction.NextCandidate, p.onNoPicture(loaded = false))
        assertEquals(RecoveryAction.TryBackupEngine, p.onNoPicture(loaded = true))
    }

    @Test
    fun withoutABackupPlayerNothingChanges() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = false)
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.DECODER, canRefresh = true))
        assertEquals(RecoveryAction.NextCandidate, p.onNoPicture(loaded = true))
    }

    @Test
    fun anUnavailableSourceMovesOnOrAsksWhenPicked() {
        assertEquals(RecoveryAction.NextCandidate, RecoveryPolicy(2, userPicked = false).onUnavailable())
        assertEquals(RecoveryAction.GiveUp, RecoveryPolicy(1, userPicked = false).onUnavailable())
        assertEquals(RecoveryAction.AskUser, RecoveryPolicy(2, userPicked = true).onUnavailable())
    }

    @Test
    fun unavailableSourcesDontUseUpTheThreeTries() {
        val p = RecoveryPolicy(candidateCount = 10, userPicked = false)
        repeat(4) { assertEquals(RecoveryAction.NextCandidate, p.onUnavailable()) }
        // Three real failures still get their turn after four skipped torrents.
        assertEquals(RecoveryAction.NextCandidate, p.onFirstFrameTimeout())
        assertEquals(RecoveryAction.NextCandidate, p.onFirstFrameTimeout())
        assertEquals(RecoveryAction.GiveUp, p.onFirstFrameTimeout())
    }
}
