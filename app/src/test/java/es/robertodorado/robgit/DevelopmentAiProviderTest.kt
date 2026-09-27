package es.robertodorado.robgit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DevelopmentAiProviderTest {
    @Test fun everyTaskProducesAnExplicitDeterministicDevelopmentResponse() = runTest {
        val provider = DevelopmentAiProvider()
        AiTask.entries.forEach { task ->
            val request = AiRequest(task, AiTestFixtures.context(), "Pregunta de prueba")
            val first = provider.complete(request)
            assertEquals(first, provider.complete(request))
            assertEquals(AiResponseSource.DEVELOPMENT, first.source)
            assertTrue(first.text.contains("no hay una IA real conectada"))
            assertTrue(first.text.length > 60)
        }
    }

    @Test fun summaryAndCommitDraftUseOnlyFilteredMetadataThroughService() = runTest {
        val service = AiAssistantService(DevelopmentAiProvider())
        val context = AiTestFixtures.context()
        val summary = service.execute(AiTask.SUMMARIZE_CHANGES, context) as AiAssistantResult.Completed
        assertTrue(summary.response.text.contains("1 modificados, 1 añadidos al índice, 1 eliminados, 1 sin seguimiento"))
        assertTrue(summary.response.text.contains("No se ha leído el contenido"))
        val draft = service.execute(AiTask.SUGGEST_COMMIT_MESSAGE, context) as AiAssistantResult.Completed
        assertTrue(draft.response.text.contains("Actualizar 4 archivos"))
        assertTrue(draft.response.text.contains("no crea ningún commit"))
    }

    @Test fun cleanSnapshotDoesNotPretendToHaveACommitDraft() = runTest {
        val context = AiTestFixtures.context().copy(changes = AiFileChanges())
        val response = DevelopmentAiProvider().complete(AiRequest(AiTask.SUGGEST_COMMIT_MESSAGE, context))
        assertTrue(response.text.contains("No se propone un mensaje"))
    }

    @Test fun blockExplanationAndStaleStatusRespectExistingRobGitMetadata() = runTest {
        val context = AiTestFixtures.context().copy(
            humanStatus = AiHumanStatus("Hay conflictos.", "RobGit ha bloqueado la operación.", blocked = true),
            gitStatus = AiTestFixtures.context().gitStatus!!.copy(hasConflicts = true, remoteStateIsFresh = false),
            error = "Error de prueba",
        )
        val provider = DevelopmentAiProvider()
        val block = provider.complete(AiRequest(AiTask.EXPLAIN_BLOCK, context))
        assertTrue(block.text.contains("RobGit ha bloqueado la operación."))
        assertTrue(block.text.contains("Error de prueba"))
        val status = provider.complete(AiRequest(AiTask.EXPLAIN_STATUS, context))
        assertTrue(status.text.contains("no está actualizado"))
    }
}
