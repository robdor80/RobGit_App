package es.robertodorado.robgit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RodoSessionTest {
    private val context = AiTestFixtures.context()

    @Test fun submitSetsThinkingBeforeProviderStartsAndForwardsTaskAndQuestion() = runTest {
        val seen = mutableListOf<AiRequest>()
        val service = AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                seen += request
                return AiResponse("Respuesta de Rodo", AiResponseSource.PROVIDER)
            }
        })
        val session = RodoSession(this, service)
        assertTrue(session.submit(AiTask.SUMMARIZE_CHANGES, context, "Detalle concreto"))
        assertTrue(session.state.value is RodoState.Thinking)
        assertTrue(seen.isEmpty())
        runCurrent()
        assertEquals(AiTask.SUMMARIZE_CHANGES, seen.single().task)
        assertEquals("Detalle concreto", seen.single().question)
        assertEquals("Respuesta de Rodo", (session.state.value as RodoState.Completed).text)
    }

    @Test fun allFiveTasksRemainInformationalAndReachProvider() = runTest {
        val seen = mutableListOf<AiTask>()
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                seen += request.task
                return AiResponse("texto", AiResponseSource.PROVIDER)
            }
        }))
        AiTask.entries.forEach { task ->
            assertTrue(session.submit(task, context, if (task == AiTask.CUSTOM_QUESTION) "Pregunta" else "Detalle"))
            runCurrent()
            assertTrue(session.state.value is RodoState.Completed)
        }
        assertEquals(AiTask.entries.toList(), seen)
    }

    @Test fun simultaneousSecondRequestIsRejectedWithoutCallingProviderTwice() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                calls++
                release.await()
                return AiResponse("primera", AiResponseSource.PROVIDER)
            }
        }))
        assertTrue(session.submit(AiTask.EXPLAIN_STATUS, context, ""))
        assertFalse(session.submit(AiTask.EXPLAIN_BLOCK, context, ""))
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit); runCurrent()
        assertEquals("primera", (session.state.value as RodoState.Completed).text)
    }

    @Test fun unavailableAndProviderFailureMapToVisibleSafeStates() = runTest {
        val provider = object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse =
                error("client_secret=must-not-appear")
        }
        val session = RodoSession(this, AiAssistantService(provider))
        session.submit(AiTask.EXPLAIN_STATUS, context.copy(gitStatus = null), "")
        runCurrent()
        val warning = session.state.value as RodoState.Warning
        assertEquals(AiAssistantService.ANALYSIS_REQUIRED_MESSAGE, warning.message)
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        val error = session.state.value as RodoState.Error
        assertTrue(error.message.contains("Rodo"))
        assertFalse(error.message.contains("client_secret"))
    }

    @Test fun emptyCustomQuestionShowsWarningWithoutCallingProvider() = runTest {
        var calls = 0
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                calls++
                return AiResponse("unexpected", AiResponseSource.PROVIDER)
            }
        }))
        session.submit(AiTask.CUSTOM_QUESTION, context, "  ")
        runCurrent()
        assertEquals(0, calls)
        assertEquals("Escribe una pregunta para Rodo.", (session.state.value as RodoState.Warning).message)
    }

    @Test fun oldResponseCannotReplaceNewRequestAfterRepositoryChange() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                calls++
                if (calls == 1) withContext(NonCancellable) { first.await() }
                return AiResponse(if (calls == 1) "old" else "new", AiResponseSource.PROVIDER)
            }
        }))
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        assertEquals(1, calls)
        session.reset()
        assertEquals(RodoState.Idle, session.state.value)
        session.submit(AiTask.EXPLAIN_STATUS, context, "new question")
        runCurrent()
        assertEquals("new", (session.state.value as RodoState.Completed).text)
        first.complete(Unit); runCurrent()
        assertEquals("new", (session.state.value as RodoState.Completed).text)
    }

    @Test fun repeatedIdenticalResponseGetsANewAnimationIdentity() = runTest {
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest) = AiResponse("mismo texto", AiResponseSource.PROVIDER)
        }))
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        val firstId = (session.state.value as RodoState.Completed).requestId
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        val secondId = (session.state.value as RodoState.Completed).requestId
        assertTrue(secondId > firstId)
    }

    @Test fun sameRepositorySelectionRetainsResponseAcrossActivityRecreationButDifferentOneResets() = runTest {
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest) = AiResponse("respuesta", AiResponseSource.PROVIDER)
        }))
        session.selectRepository("repo-one")
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        val answer = session.state.value
        session.selectRepository("repo-one")
        assertEquals(answer, session.state.value)
        session.selectRepository("repo-two")
        assertEquals(RodoState.Idle, session.state.value)
    }

    @Test fun sameRepositorySelectionKeepsPendingRequestDuringRotation() = runTest {
        val release = CompletableDeferred<Unit>()
        val session = RodoSession(this, AiAssistantService(object : AiProvider {
            override suspend fun complete(request: AiRequest): AiResponse {
                release.await()
                return AiResponse("respuesta tras rotar", AiResponseSource.PROVIDER)
            }
        }))
        session.selectRepository("repo-one")
        session.submit(AiTask.EXPLAIN_STATUS, context, "")
        runCurrent()
        val pending = session.state.value as RodoState.Thinking
        session.selectRepository("repo-one")
        assertEquals(pending, session.state.value)
        release.complete(Unit); runCurrent()
        assertEquals("respuesta tras rotar", (session.state.value as RodoState.Completed).text)
    }
}
