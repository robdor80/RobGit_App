package es.robertodorado.robgit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiAssistantServiceTest {
    private class RecordingProvider : AiProvider {
        val requests = mutableListOf<AiRequest>()
        val response = AiResponse("Respuesta de prueba.", AiResponseSource.DEVELOPMENT)
        override suspend fun complete(request: AiRequest): AiResponse {
            requests += request
            return response
        }
    }

    @Test fun stateDependentTasksAreBlockedBeforeProviderWhenAnalysisIsMissing() = runTest {
        val provider = RecordingProvider()
        val service = AiAssistantService(provider)
        val context = AiTestFixtures.context().copy(gitStatus = null)
        AiTask.entries.filter { it != AiTask.CUSTOM_QUESTION }.forEach { task ->
            val result = service.execute(task, context)
            assertEquals(AiAssistantResult.Unavailable("Primero analiza el repositorio pulsando el logo de RobGit."), result)
        }
        assertTrue(provider.requests.isEmpty())
        assertNull(context.gitStatus)
    }

    @Test fun customQuestionCanRunWithoutAnalysisAndNeverFillsInGitState() = runTest {
        val provider = RecordingProvider()
        val context = AiTestFixtures.context().copy(gitStatus = null)
        val result = AiAssistantService(provider).execute(AiTask.CUSTOM_QUESTION, context, "  ¿Qué es un commit?  ")
        assertEquals(AiAssistantResult.Completed(provider.response), result)
        val sent = provider.requests.single()
        assertEquals(AiTask.CUSTOM_QUESTION, sent.task)
        assertEquals("¿Qué es un commit?", sent.question)
        assertNull(sent.context.gitStatus)
    }

    @Test fun emptyCustomQuestionIsBlockedBeforeProvider() = runTest {
        val provider = RecordingProvider()
        val result = AiAssistantService(provider).execute(AiTask.CUSTOM_QUESTION, AiTestFixtures.context(), "  ")
        assertEquals(AiAssistantResult.Unavailable("Escribe una pregunta para Rodo."), result)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun dispatchesEveryTaskWithExistingContextAndReturnsProviderResponse() = runTest {
        val provider = RecordingProvider()
        val service = AiAssistantService(provider)
        val context = AiTestFixtures.context()
        AiTask.entries.forEach { task ->
            assertEquals(AiAssistantResult.Completed(provider.response), service.execute(task, context, "Detalle útil"))
        }
        assertEquals(AiTask.entries.toList(), provider.requests.map { it.task })
        assertTrue(provider.requests.all { it.context == context && it.question == "Detalle útil" })
    }

    @Test fun filtersAtProviderBoundaryEvenWhenCallerBypassesContextBuilder() = runTest {
        val provider = RecordingProvider()
        val raw = AiTestFixtures.context().copy(
            changes = AiFileChanges(modified = listOf("safe.txt", ".git/config", "local.properties")),
            error = "client_secret=never-forward-this-test-value",
        )
        AiAssistantService(provider).execute(AiTask.EXPLAIN_BLOCK, raw, "Bearer never-forward-this-other-value")
        val request = provider.requests.single()
        assertEquals(listOf("safe.txt"), request.context.changes.modified)
        assertEquals(2, request.context.omittedFileCount)
        assertFalse(request.toString().contains("never-forward"))
        assertTrue(raw.error!!.contains("never-forward"))
        assertEquals(3, raw.changes.modified.size)
    }

    @Test fun boundsTheQuestionBeforeCallingProvider() = runTest {
        val provider = RecordingProvider()
        AiAssistantService(provider).execute(AiTask.CUSTOM_QUESTION, AiTestFixtures.context(), "x".repeat(10_000))
        assertEquals(AiContextFilter.MAX_QUESTION, provider.requests.single().question!!.length)
    }

    @Test fun providerFailureNeverLeaksExceptionMessageOrRetries() = runTest {
        var calls = 0
        val provider = object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                calls++
                error("client_secret=private-test-exception")
            }
        }
        val result = AiAssistantService(provider).execute(AiTask.EXPLAIN_STATUS, AiTestFixtures.context())
        assertTrue(result is AiAssistantResult.Failed)
        assertFalse(result.toString().contains("private-test-exception"))
        assertEquals(1, calls)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAnErrorResponse() = runTest {
        val provider = object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse = throw CancellationException("Cancelled test")
        }
        try {
            AiAssistantService(provider).execute(AiTask.EXPLAIN_STATUS, AiTestFixtures.context())
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Closing the dialog can cancel its request.
        }
    }
}
