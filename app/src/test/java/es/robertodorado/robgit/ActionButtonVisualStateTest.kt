package es.robertodorado.robgit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ActionButtonVisualStateTest {
    private val actions = RepositoryAction.entries

    @Test fun analyzingToSynchronizedKeepsAllFourBorders() {
        val analyzing = RepositoryStatusPresenter.analyzing
        val synchronized = RepositoryStatusPresenter.present(snapshot(CommitRelation.SYNCHRONIZED))

        actions.forEach { action ->
            assertVisibleBorder(actionButtonVisualState(analyzing, screenEnabled = false, action))
            assertVisibleBorder(actionButtonVisualState(synchronized, screenEnabled = true, action))
        }
        assertFalse(actionButtonVisualState(synchronized, true, RepositoryAction.PULL).enabled)
        assertFalse(actionButtonVisualState(synchronized, true, RepositoryAction.PUSH).enabled)
        assertTrue(actionButtonVisualState(synchronized, true, RepositoryAction.SYNCHRONIZE).enabled)
        assertTrue(actionButtonVisualState(synchronized, true, RepositoryAction.AI).enabled)
    }

    @Test fun bordersRemainConfiguredAcrossOtherMainStates() {
        val statuses = listOf(
            RepositoryStatusPresenter.present(snapshot(CommitRelation.REMOTE_AHEAD, behind = 1)),
            RepositoryStatusPresenter.present(snapshot(CommitRelation.LOCAL_AHEAD, ahead = 1)),
            RepositoryStatusPresenter.present(snapshot(CommitRelation.DIVERGED, ahead = 1, behind = 1)),
            RepositoryStatusPresenter.present(snapshot(CommitRelation.UNDETERMINED, fresh = false)),
            RepositoryStatusPresenter.authRequired(),
            RepositoryStatusPresenter.authFailed(),
            RepositoryStatusPresenter.pushUncertain(),
        )
        statuses.forEach { status ->
            actions.forEach { action ->
                assertVisibleBorder(actionButtonVisualState(status, screenEnabled = true, action))
            }
        }
    }

    private fun assertVisibleBorder(visual: ActionButtonVisualState) {
        assertEquals(1.dp, visual.borderWidth)
        assertTrue(visual.borderAlpha > 0f)
    }

    private fun snapshot(
        relation: CommitRelation,
        ahead: Int = 0,
        behind: Int = 0,
        fresh: Boolean = true,
    ) = RepositoryStateSnapshot(
        type = when (relation) {
            CommitRelation.SYNCHRONIZED -> RepositoryStateType.SYNCHRONIZED
            CommitRelation.REMOTE_AHEAD -> RepositoryStateType.REMOTE_AHEAD
            CommitRelation.LOCAL_AHEAD -> RepositoryStateType.LOCAL_AHEAD
            CommitRelation.DIVERGED -> RepositoryStateType.DIVERGED
            CommitRelation.UNDETERMINED -> RepositoryStateType.ERROR
        },
        relation = relation,
        message = "",
        branch = "main",
        localHead = "a".repeat(40),
        remoteHead = "b".repeat(40),
        ahead = ahead,
        behind = behind,
        changes = WorkingTreeChanges(),
        checkedAt = Instant.EPOCH,
        remoteStateIsFresh = fresh,
    )
}
