package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class AiContextBuilderTest {
    @Test fun copiesExistingMetadataAndSeparatesAddedFromUntrackedFiles() {
        val context = AiTestFixtures.context()
        assertEquals("Repositorio de prueba", context.repositoryName)
        assertEquals("main", context.branch)
        assertEquals("LOCAL_CHANGES", context.gitStatus?.state)
        assertEquals("LOCAL_AHEAD", context.gitStatus?.relation)
        assertEquals(2, context.gitStatus?.ahead)
        assertEquals(0, context.gitStatus?.behind)
        assertEquals("2026-09-28T10:00:00Z", context.gitStatus?.checkedAt)
        assertEquals(listOf("added.txt"), context.changes.added)
        assertEquals(listOf("untracked.txt"), context.changes.untracked)
        assertEquals(listOf("modified.txt"), context.changes.modified)
        assertEquals(listOf("deleted.txt"), context.changes.deleted)
        assertEquals(listOf("added.txt", "deleted.txt", "modified.txt"), context.changes.staged)
        assertEquals("ANALIZAR AHORA", context.lastOperation?.name)
        assertEquals("Hay cambios locales.", context.message)
    }

    @Test fun absentAnalysisStaysAbsentWithoutInventingRepositoryState() {
        val context = AiContextBuilder().build(AiTestFixtures.repository, null, RepositoryStatusPresenter.notUpdated)
        assertNull(context.gitStatus)
        assertEquals(AiFileChanges(), context.changes)
        assertEquals("Estado sin actualizar", context.humanStatus.title)
        assertEquals("main", context.branch)
        assertFalse(context.humanStatus.blocked)
    }

    @Test fun remoteIdentityWorkspaceAndGitObjectsAreNotCopiedToAiContext() {
        val context = AiTestFixtures.context().toString()
        listOf(AiTestFixtures.repository.id, AiTestFixtures.repository.remoteUrl,
            AiTestFixtures.repository.localDirectoryName, "local-object-not-for-ai", "remote-object-not-for-ai")
            .forEach { assertFalse(context.contains(it)) }
    }

    @Test fun copiesDivergenceConflictsAndFailureFlagsFromActualSnapshot() {
        val state = AiTestFixtures.state().copy(
            relation = CommitRelation.DIVERGED, behind = 3, branch = "feature/current",
            remoteStateIsFresh = false, authenticationRequired = true, authenticationRejected = true,
            repositoryAccessDenied = true, error = "FETCH_ERROR",
            changes = WorkingTreeChanges(conflictingFiles = setOf("conflict.txt")),
        )
        val context = AiContextBuilder().build(AiTestFixtures.repository, state, RepositoryStatusPresenter.present(state))
        assertEquals("feature/current", context.branch)
        assertTrue(context.gitStatus!!.diverged)
        assertTrue(context.gitStatus.hasConflicts)
        assertFalse(context.gitStatus.remoteStateIsFresh)
        assertTrue(context.gitStatus.authenticationRequired)
        assertTrue(context.gitStatus.authenticationRejected)
        assertTrue(context.gitStatus.repositoryAccessDenied)
        assertEquals(3, context.gitStatus.behind)
        assertEquals(listOf("conflict.txt"), context.changes.conflicting)
        assertEquals("FETCH_ERROR", context.error)
    }

    @Test fun builderReturnsAnIndependentSnapshotAndFiltersSensitivePaths() {
        val mutablePaths = mutableSetOf("before.txt", "local.properties")
        val state = AiTestFixtures.state().copy(changes = WorkingTreeChanges(newFiles = mutablePaths))
        val context = AiContextBuilder().build(null, state, RepositoryStatusPresenter.present(state))
        mutablePaths += "after.txt"
        assertEquals(listOf("before.txt"), context.changes.untracked)
        assertEquals(1, context.omittedFileCount)
        assertNull(context.repositoryName)
    }
}
