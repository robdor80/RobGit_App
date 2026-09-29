package es.robertodorado.robgit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryGitConcurrencyTest {
    @Test fun foregroundAndLocalReadCannotRunDuringGitOperation() = runTest {
        val release = CompletableDeferred<Unit>()
        var localReads = 0
        val operation = launch { RepositoryGitConcurrency.run { release.await() } }
        runCurrent()
        assertTrue(RepositoryGitConcurrency.isBusy)
        val local = launch { RepositoryGitConcurrency.run { localReads++ } }
        runCurrent()
        assertEquals(0, localReads)
        release.complete(Unit); runCurrent()
        operation.join(); local.join()
        assertEquals(1, localReads)
        assertFalse(RepositoryGitConcurrency.isBusy)
    }

    @Test fun cancelledActivityKeepsLockUntilNonCancellableGitCallReturns() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val oldActivity = launch {
            RepositoryGitConcurrency.run {
                withContext(NonCancellable) { release.await(); order += "old Git finished" }
            }
        }
        runCurrent(); oldActivity.cancel(); runCurrent()
        val newActivity = launch { RepositoryGitConcurrency.run { order += "new Git started" } }
        runCurrent()
        assertTrue(order.isEmpty())
        release.complete(Unit); runCurrent()
        oldActivity.join(); newActivity.join()
        assertEquals(listOf("old Git finished", "new Git started"), order)
        assertFalse(RepositoryGitConcurrency.isBusy)
    }

    @Test fun failureReleasesProcessLock() = runTest {
        try { RepositoryGitConcurrency.run { throw IllegalStateException("failed") } }
        catch (_: IllegalStateException) { }
        assertFalse(RepositoryGitConcurrency.isBusy)
        assertEquals("next", RepositoryGitConcurrency.run { "next" })
    }
}
