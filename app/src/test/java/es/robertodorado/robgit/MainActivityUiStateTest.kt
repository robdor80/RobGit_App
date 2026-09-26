package es.robertodorado.robgit

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MainActivityUiStateTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun repositoryStateSaverRestoresVisibleSynchronizedState() {
        val original = RepositoryStateSnapshot(
            type = RepositoryStateType.SYNCHRONIZED,
            relation = CommitRelation.SYNCHRONIZED,
            message = "Todo está sincronizado con GitHub.",
            branch = "main",
            localHead = "a".repeat(40),
            remoteHead = "a".repeat(40),
            ahead = 0,
            behind = 0,
            changes = WorkingTreeChanges(),
            checkedAt = Instant.parse("2026-09-26T00:00:00Z"),
            remoteStateIsFresh = true,
        )

        val saved = with(repositoryStateSaver) { saverScope.save(original) }
        val restored = repositoryStateSaver.restore(requireNotNull(saved))

        assertEquals(original, restored)
        assertEquals(RepositoryStateType.SYNCHRONIZED, restored?.type)
        assertEquals(0, restored?.ahead)
        assertEquals(0, restored?.behind)
    }

    @Test
    fun lastOperationSaverRestoresResultMessageWithoutToken() {
        val original = RestoredOperation(
            operation = "↕ SINCRONIZAR",
            outcome = "NOTHING_TO_DO",
            message = "Ya está sincronizado.",
            detail = null,
        )

        val saved = with(lastOperationSaver) { saverScope.save(original) }
        val restored = lastOperationSaver.restore(requireNotNull(saved))

        assertEquals(original, restored)
        assertEquals("Ya está sincronizado.", restored?.message)
        assertNull(restored?.detail)
        assertTrue(saved.toString().contains("NOTHING_TO_DO"))
        assertTrue(!saved.toString().contains("token"))
    }
}
