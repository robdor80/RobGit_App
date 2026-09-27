package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RepositoryHumanStatusTest {
    @Test fun noRepositoriesShowsAnEmptyState() {
        assertEquals("No tienes repositorios configurados.", RepositoryStatusPresenter.noRepositories.title)
    }

    @Test fun preparedRepositoryWithoutFreshStateRequestsManualAnalysis() {
        val shown = RepositoryStatusPresenter.notUpdated
        assertEquals("Estado sin actualizar", shown.title)
        assertEquals("Pulsa el logo de RobGit para analizar el repositorio.", shown.explanation)
        assertFalse(shown.blocked)
    }

    @Test fun missingConfiguredBranchGetsHumanExplanation() {
        val shown = RepositoryStatusPresenter.present(
            state(RepositoryStateType.ERROR, CommitRelation.UNDETERMINED, fresh = false)
                .copy(error = "refs/remotes/origin/develop no existe después del fetch"),
        )
        assertEquals("La rama configurada no está disponible.", shown.title)
    }

    private fun state(
        type: RepositoryStateType,
        relation: CommitRelation,
        ahead: Int = 0,
        behind: Int = 0,
        changes: WorkingTreeChanges = WorkingTreeChanges(),
        fresh: Boolean = true,
    ) = RepositoryStateSnapshot(
        type, relation, "technical", "main", "a".repeat(40), "b".repeat(40),
        ahead, behind, changes, Instant.EPOCH, fresh,
    )

    @Test fun synchronizedIsHumanAndNeedsNoAction() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED))
        assertEquals("Todo está al día.", shown.title)
        assertNull(shown.recommendedAction)
    }

    @Test fun remoteAheadRecommendsAndEnablesPull() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, behind = 2))
        assertEquals(RepositoryAction.PULL, shown.recommendedAction)
        assertTrue(shown.pullEnabled)
        assertFalse(shown.pushEnabled)
    }

    @Test fun localChangesRecommendAndEnablePush() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED, changes = WorkingTreeChanges(modifiedFiles = setOf("a"))))
        assertEquals(RepositoryAction.PUSH, shown.recommendedAction)
        assertTrue(shown.pushEnabled)
    }

    @Test fun localAheadRecommendsAndEnablesPush() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, ahead = 1))
        assertEquals(RepositoryAction.PUSH, shown.recommendedAction)
        assertTrue(shown.pushEnabled)
    }

    @Test fun changesOnBothSidesAreBlocked() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.REMOTE_AHEAD, behind = 1, changes = WorkingTreeChanges(newFiles = setOf("a"))))
        assertTrue(shown.blocked)
        assertTrue(shown.title.contains("dos sitios"))
        assertFalse(shown.pullEnabled || shown.pushEnabled)
    }

    @Test fun divergedIsBlocked() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.DIVERGED, CommitRelation.DIVERGED, ahead = 1, behind = 1))
        assertTrue(shown.blocked)
        assertTrue(shown.title.contains("separado"))
    }

    @Test fun conflictsTakePriorityAndAreBlocked() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED, changes = WorkingTreeChanges(conflictingFiles = setOf("a"))))
        assertTrue(shown.blocked)
        assertEquals("Hay conflictos pendientes.", shown.title)
    }

    @Test fun fetchErrorDoesNotClaimSynchronization() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.ERROR, CommitRelation.UNDETERMINED, fresh = false))
        assertEquals("No he podido comprobar GitHub.", shown.title)
        assertTrue(shown.blocked)
    }

    @Test fun authenticationMessagesAreExplicit() {
        assertTrue(RepositoryStatusPresenter.authRequired().explanation.contains("Ajustes"))
        assertTrue(RepositoryStatusPresenter.authFailed().title.contains("volver a conectar"))
        assertTrue(RepositoryStatusPresenter.pushUncertain().title.contains("confirmar"))
    }

    @Test fun actionMatrixDisablesUnsafeDirectionalActions() {
        val synchronized = RepositoryStatusPresenter.present(state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED))
        val diverged = RepositoryStatusPresenter.present(state(RepositoryStateType.DIVERGED, CommitRelation.DIVERGED))
        assertFalse(synchronized.pullEnabled || synchronized.pushEnabled)
        assertFalse(diverged.pullEnabled || diverged.pushEnabled)
    }

    @Test fun rotationDoesNotRequestRefresh() {
        val policy = ForegroundRefreshPolicy()
        assertFalse(policy.onStart())
        policy.onStop(isChangingConfigurations = true)
        assertFalse(policy.onStart())
    }

    @Test fun realBackgroundReturnRequestsRefresh() {
        val policy = ForegroundRefreshPolicy()
        assertFalse(policy.onStart())
        policy.onStop(isChangingConfigurations = false)
        assertTrue(policy.onStart())
    }
}
