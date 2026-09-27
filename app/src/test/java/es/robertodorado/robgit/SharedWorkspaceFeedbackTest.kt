package es.robertodorado.robgit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SharedWorkspaceFeedbackTest {
    @Test fun prepareAndFilesystemHavePersistentHumanHeadlines() {
        assertEquals("Workspace preparado correctamente", feedback("PREPARAR WORKSPACE").title)
        assertEquals("Prueba de filesystem completada correctamente", feedback("PRUEBA FILESYSTEM").title)
    }

    @Test fun analyzeReportsBranchRelationsAndCleanWorkingTree() {
        val result = feedback("ANALIZAR WORKSPACE", state = state())
        assertEquals("Repositorio analizado correctamente", result.title)
        assertTrue(result.detail.contains("Rama: main"))
        assertTrue(result.detail.contains("Ahead: 0"))
        assertTrue(result.detail.contains("Behind: 0"))
        assertTrue(result.detail.contains("Working tree: limpio"))
    }

    @Test fun analyzeNamesExternalFilesDetectedByJGit() {
        val changes = WorkingTreeChanges(newFiles = setOf("external_edit_test.txt"))
        val result = feedback("ANALIZAR WORKSPACE", state = state(changes))
        assertTrue(result.detail.contains("Working tree: con cambios"))
        assertTrue(result.detail.contains("Se detectó 1 archivo nuevo: external_edit_test.txt"))
    }

    @Test fun pullPushAndSynchronizationReportTheirSuccessfulOutcome() {
        assertEquals("PULL completado correctamente", feedback("PULL DE PRUEBA", DownloadOutcome.SUCCESS.name).title)
        assertEquals("PUSH completado correctamente", feedback("PUSH DE PRUEBA", UploadOutcome.SUCCESS.name).title)
        assertEquals("Sincronización completada: cambios descargados",
            feedback("SINCRONIZAR WORKSPACE", SynchronizationOutcome.SUCCESS_DOWNLOADED.name).title)
    }

    @Test fun blockedOperationsKeepTheHumanErrorFromTheGitService() {
        val result = feedback("PULL DE PRUEBA", success = false, message = "Hay cambios locales. No se realizó ningún cambio.")
        assertEquals("La operación no se completó", result.title)
        assertEquals("Hay cambios locales. No se realizó ningún cambio.", result.detail)
    }

    @Test fun authorizationRequestIsPresentedClearly() {
        val result = sharedWorkspaceFeedback(
            SharedWorkspaceResult("PUSH DE PRUEBA", false, "Conecta RobGit con GitHub desde Ajustes.", "/test",
                authenticationRequired = true),
        )
        assertEquals("Hace falta autorización de GitHub", result.title)
        assertEquals("Conecta RobGit con GitHub desde Ajustes.", result.detail)
    }

    private fun feedback(
        operation: String,
        outcome: String? = null,
        success: Boolean = true,
        message: String = "operación",
        state: RepositoryStateSnapshot? = null,
    ) = sharedWorkspaceFeedback(SharedWorkspaceResult(operation, success, message, "/workspace", state, outcome))

    private fun state(changes: WorkingTreeChanges = WorkingTreeChanges()) = RepositoryStateSnapshot(
        type = if (changes.hasChanges) RepositoryStateType.LOCAL_CHANGES else RepositoryStateType.SYNCHRONIZED,
        relation = CommitRelation.SYNCHRONIZED,
        message = "state",
        branch = "main",
        localHead = "abc",
        remoteHead = "abc",
        ahead = 0,
        behind = 0,
        changes = changes,
        checkedAt = Instant.EPOCH,
        remoteStateIsFresh = true,
    )
}
