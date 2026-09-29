package es.robertodorado.robgit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryAutoRefreshTest {
    private val first = RepositoryWatchTarget("one", File("one"))
    private val second = RepositoryWatchTarget("two", File("two"))

    @Test fun simpleLocalEventRefreshesOnlyAfterDebounce() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        h.watches.single().change()
        runCurrent(); advanceTimeBy(749); runCurrent()
        assertTrue(h.published.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("one"), h.reads)
        h.controller.close()
    }

    @Test fun burstIncludingRenameAndTemporarySaveRestartsTimerOnce() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        repeat(6) {
            h.watches.single().change(); runCurrent(); advanceTimeBy(200)
        }
        advanceTimeBy(549); runCurrent()
        assertTrue(h.reads.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, h.reads.size)
        h.controller.close()
    }

    @Test fun separatedEventsRefreshSeparately() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        repeat(3) {
            h.watches.single().change(); runCurrent(); advanceTimeBy(751); runCurrent()
        }
        assertEquals(3, h.reads.size)
        h.controller.close()
    }

    @Test fun largeBurstUsesABoundedMailbox() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        repeat(10_000) { h.watches.single().change() }
        runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(1, h.reads.size)
        h.controller.close()
    }

    @Test fun changingRepositoryClosesPreviousAndDiscardsItsLateCallbacks() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        val old = h.watches.single()
        old.change(); runCurrent(); advanceTimeBy(100)
        h.controller.select(second); runCurrent()
        assertTrue(old.closed)
        old.change(); runCurrent(); advanceTimeBy(750); runCurrent()
        assertTrue(h.reads.isEmpty())
        h.watches.last().change(); runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(listOf("two"), h.reads)
        h.controller.close()
    }

    @Test fun removedUnpreparedOrInaccessibleTargetStopsObservation() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        h.watches.single().change(); runCurrent()
        h.controller.select(null); runCurrent()
        advanceTimeBy(800); runCurrent()
        assertTrue(h.watches.single().closed)
        assertTrue(h.reads.isEmpty())
        h.controller.close()
    }

    @Test fun ownGitOperationsDiscardPendingEventsAndKeepTheirFinalState() = runTest {
        for (operation in listOf("PULL", "PUSH", "SINCRONIZAR")) {
            val h = Harness(this)
            h.controller.select(first); runCurrent()
            val old = h.watches.single()
            old.change(); runCurrent(); advanceTimeBy(200)
            h.controller.operationActive(true)
            repeat(200) { old.change() }
            h.published += snapshot(operation)
            h.controller.operationActive(false); runCurrent()
            advanceTimeBy(800); runCurrent()
            assertEquals(operation, h.published.single().message)
            assertTrue(h.reads.isEmpty())
            assertTrue(old.closed)
            assertEquals(2, h.watches.size)
            h.controller.close()
        }
    }

    @Test fun inFlightOldReadCannotOverwriteOperationFinalState() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = Harness(this) {
            withContext(NonCancellable) { release.await() }
            snapshot("late local state")
        }
        h.controller.select(first); runCurrent()
        h.watches.single().change(); runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(1, h.reads.size)
        h.controller.operationActive(true)
        h.published += snapshot("PULL final")
        h.controller.operationActive(false)
        release.complete(Unit); runCurrent()
        assertEquals("PULL final", h.published.single().message)
        h.controller.close()
    }

    @Test fun changesDuringReadAreReadAgainWithoutCancellingCurrentRead() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = Harness(this) { release.await(); snapshot("local") }
        h.controller.select(first); runCurrent()
        h.watches.single().change(); runCurrent(); advanceTimeBy(750); runCurrent()
        repeat(20) { h.watches.single().change() }
        runCurrent()
        assertEquals(1, h.reads.size)
        release.complete(Unit); runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(2, h.reads.size)
        assertEquals(2, h.published.size)
        h.controller.close()
    }

    @Test fun accessFailureClosesObserversWithoutRetryLoop() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        h.watches.single().failure(WorkspaceAccessException("denied"))
        runCurrent(); advanceTimeBy(750); runCurrent()
        assertEquals(1, h.failures.size)
        assertTrue(h.watches.single().closed)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(h.reads.isEmpty())
        h.controller.close()
    }

    @Test fun errorCannotBeOverwrittenByAConflatedChangeEvent() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        h.watches.single().failure(WorkspaceAccessException("denied"))
        repeat(500) { h.watches.single().change() }
        runCurrent()
        assertEquals(1, h.failures.size)
        assertTrue(h.reads.isEmpty())
        assertTrue(h.watches.single().closed)
        h.controller.close()
    }

    @Test fun realBackgroundReturnRequestsFullAnalyzeOnceAndRotationDoesNot() = runTest {
        val h = Harness(this)
        val policy = ForegroundRefreshPolicy()
        var fullAnalyses = 0
        fun start() {
            if (policy.onStart() && h.controller.shouldAnalyzeOnForeground(true, false)) fullAnalyses++
        }
        start()
        policy.onStop(true); start()
        assertEquals(0, fullAnalyses)
        policy.onStop(false); start(); start()
        assertEquals(1, fullAnalyses)
        h.controller.close()
    }

    @Test fun busyForegroundReturnIsSkippedRatherThanQueued() = runTest {
        val h = Harness(this)
        assertFalse(h.controller.shouldAnalyzeOnForeground(false, false))
        assertFalse(h.controller.shouldAnalyzeOnForeground(true, true))
        h.controller.operationActive(true)
        assertFalse(h.controller.shouldAnalyzeOnForeground(true, false))
        h.controller.operationActive(false)
        assertTrue(h.reads.isEmpty())
        h.controller.close()
        assertFalse(h.controller.shouldAnalyzeOnForeground(true, false))
    }

    @Test fun identicalTargetDoesNotRecreateObservers() = runTest {
        val h = Harness(this)
        h.controller.select(first); runCurrent()
        h.controller.select(first); runCurrent()
        assertEquals(1, h.watches.size)
        h.controller.close(); runCurrent()
        assertTrue(h.watches.single().closed)
    }

    private class Watch(val change: () -> Unit, val failure: (Exception) -> Unit) : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    private class Harness(scope: TestScope, read: (suspend () -> RepositoryStateSnapshot)? = null) {
        val watches = mutableListOf<Watch>()
        val reads = mutableListOf<String>()
        val published = mutableListOf<RepositoryStateSnapshot>()
        val failures = mutableListOf<Exception>()
        val controller = RepositoryAutoRefresh(scope,
            RepositoryWatcherFactory { _, change, failure -> Watch(change, failure).also { watches += it } },
            { target -> reads += target.repositoryId; read?.invoke() ?: snapshot("local") },
            { published += it }, { failures += it })
    }

    companion object {
        private fun snapshot(message: String) = RepositoryStateSnapshot(RepositoryStateType.LOCAL_CHANGES,
            CommitRelation.SYNCHRONIZED, message, "main", "head", "head", 0, 0,
            WorkingTreeChanges(modifiedFiles = setOf("file")), Instant.EPOCH, false)
    }
}
