package es.robertodorado.robgit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class GitHubGitOperationsTest {
    private val directory = File("unused-test-repository")

    @Test fun publicRepositoryReadsAnonymouslyWhenDisconnected() = runTest {
        val provider = FakeProvider()
        val engine = FakeEngine()
        val operations = GitHubGitOperations(provider)
        assertEquals(RepositoryStateType.SYNCHRONIZED, operations.analyze(engine, directory).type)
        assertEquals(DownloadOutcome.ALREADY_SYNCHRONIZED, operations.pull(engine, directory).outcome)
        assertEquals(listOf("", ""), engine.credentialsSeen)
    }

    @Test fun privateRepositoryWithoutSessionRequiresConnection() = runTest {
        val engine = FakeEngine().apply { privateRepository = true }
        val result = GitHubGitOperations(FakeProvider()).analyze(engine, directory)
        assertTrue(result.authenticationRequired)
        assertEquals("", engine.credentialsSeen.single())
    }

    @Test fun connectedRepositoryGetsOAuthTokenAndClearsTemporaryArray() = runTest {
        val provider = FakeProvider(GitHubConnectionState.Connected("user"), "oauth-test-value")
        val engine = FakeEngine()
        GitHubGitOperations(provider).analyze(engine, directory)
        assertEquals("oauth-test-value", engine.credentialsSeen.single())
        assertTrue(engine.lastArray!!.all { it == '\u0000' })
    }

    @Test fun measuredPullKeepsLegacyEngineCompatibilityAndTimesOAuthAndTotal() = runTest {
        var now = 0L
        val performance = PullPerformanceRecorder { now++ }
        val provider = FakeProvider(GitHubConnectionState.Connected("user"), "oauth-test-value")
        val engine = FakeEngine()
        val result = GitHubGitOperations(provider).pull(engine, directory, performance)
        assertEquals(DownloadOutcome.ALREADY_SYNCHRONIZED, result.outcome)
        assertEquals("oauth-test-value", engine.credentialsSeen.single())
        assertTrue(engine.lastArray!!.all { it == '\u0000' })
        val report = performance.snapshot()
        assertEquals(listOf(PullPhase.OAUTH, PullPhase.TOKEN_COPY, PullPhase.OAUTH_CLEANUP, PullPhase.TOTAL),
            report.timings.map { it.phase })
        assertEquals(7L, report.totalNanos)
        assertFalse(report.toString().contains("oauth-test-value"))
    }

    @Test fun failedOAuthPullStillRecordsPartialDiagnosticWithoutCallingEngine() = runTest {
        val performance = PullPerformanceRecorder { 0 }
        val provider = FakeProvider(GitHubConnectionState.Connected("user")).apply {
            failureState = GitHubConnectionState.Error("private-test-error", retryable = true)
        }
        val engine = FakeEngine()
        try {
            GitHubGitOperations(provider).pull(engine, directory, performance)
            fail("Expected authentication failure")
        } catch (_: GitAccessUnavailableException) {
            assertTrue(engine.credentialsSeen.isEmpty())
        }
        assertEquals(listOf(PullPhase.OAUTH, PullPhase.TOTAL), performance.snapshot().timings.map { it.phase })
        assertFalse(performance.snapshot().toString().contains("private-test-error"))
    }

    @Test fun pushWithoutSessionStopsBeforeEngineCall() = runTest {
        val engine = FakeEngine()
        try {
            GitHubGitOperations(FakeProvider()).push(engine, directory, "change")
            fail("Expected authentication requirement")
        } catch (failure: GitAccessUnavailableException) {
            assertTrue(failure.message.orEmpty().contains("Ajustes"))
        }
        assertEquals(0, engine.pushCalls)
    }

    @Test fun needsReauthDoesNotAskForPatOrCallPush() = runTest {
        val provider = FakeProvider(GitHubConnectionState.NeedsReauth)
        val engine = FakeEngine()
        try {
            GitHubGitOperations(provider).push(engine, directory, "change")
            fail("Expected reconnection requirement")
        } catch (failure: GitAccessUnavailableException) {
            assertTrue(failure.message.orEmpty().contains("volver a conectar"))
        }
        assertEquals(0, engine.pushCalls)
    }

    @Test fun refreshedTokenIsDeliveredBeforeGitOperation() = runTest {
        val provider = FakeProvider(GitHubConnectionState.Connected("user"), "renewed-test-value")
        val engine = FakeEngine()
        GitHubGitOperations(provider).push(engine, directory, "change")
        assertEquals("renewed-test-value", engine.credentialsSeen.single())
        assertEquals(1, engine.pushCalls)
    }

    @Test fun temporaryRefreshFailureStopsWithoutChangingLocalGit() = runTest {
        val provider = FakeProvider(GitHubConnectionState.Connected("user")).apply {
            failureState = GitHubConnectionState.Error("oauth-sensitive-test-value", retryable = true)
        }
        val engine = FakeEngine()
        try {
            GitHubGitOperations(provider).synchronize(engine, directory, "change")
            fail("Expected network failure")
        } catch (failure: GitAccessUnavailableException) {
            assertTrue(failure.message.orEmpty().contains("red"))
            assertFalse(failure.message.orEmpty().contains("oauth-sensitive-test-value"))
        }
        assertEquals(0, engine.synchronizeCalls)
    }

    @Test fun clearTokenRejectionInvalidatesSessionWithoutRetryingPush() = runTest {
        val provider = FakeProvider(GitHubConnectionState.Connected("user"), "oauth-test-value")
        val engine = FakeEngine().apply { rejectToken = true }
        val result = GitHubGitOperations(provider).push(engine, directory, "change")
        assertEquals(UploadOutcome.AUTH_FAILED, result.outcome)
        assertEquals(1, provider.rejections)
        assertEquals(GitHubConnectionState.NeedsReauth, provider.state.value)
        assertEquals(1, engine.pushCalls)
    }

    @Test fun privateCloneUsesOAuthAndPublicCloneCanBeAnonymous() = runTest {
        val privateEngine = FakeEngine().apply { privateRepository = true }
        assertTrue(GitHubGitOperations(FakeProvider(GitHubConnectionState.Connected("user"), "oauth-test-value"))
            .prepare(privateEngine, directory).success)
        assertEquals("oauth-test-value", privateEngine.credentialsSeen.single())
        val publicEngine = FakeEngine()
        assertTrue(GitHubGitOperations(FakeProvider()).prepare(publicEngine, directory).success)
        assertEquals("", publicEngine.credentialsSeen.single())
    }

    @Test fun unavailableRepositoryDoesNotInvalidateOtherwiseValidSession() = runTest {
        val provider = FakeProvider(GitHubConnectionState.Connected("user"), "oauth-test-value")
        val engine = FakeEngine().apply { privateRepository = true; denyRepository = true }
        val result = GitHubGitOperations(provider).analyze(engine, directory)
        assertTrue(result.repositoryAccessDenied)
        assertEquals(0, provider.rejections)
        assertEquals(GitHubConnectionState.Connected("user"), provider.state.value)
    }

    @Test fun synchronizationKeepsEngineDecisionAndUsesAnonymousRead() = runTest {
        val engine = FakeEngine()
        val result = GitHubGitOperations(FakeProvider()).synchronize(engine, directory, "change")
        assertEquals(SynchronizationOutcome.NOTHING_TO_DO, result.outcome)
        assertEquals("", engine.credentialsSeen.single())
        assertEquals(1, engine.synchronizeCalls)
    }

    private class FakeProvider(
        initialState: GitHubConnectionState = GitHubConnectionState.Disconnected,
        private val token: String? = null,
    ) : GitHubAccessTokenProvider {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<GitHubConnectionState> = mutableState
        var failureState: GitHubConnectionState? = null
        var rejections = 0
        override suspend fun validAccessToken(): String? {
            failureState?.let { mutableState.value = it; return null }
            return token
        }
        override suspend fun reportAuthenticationRejected() {
            rejections++
            mutableState.value = GitHubConnectionState.NeedsReauth
        }
    }

    private class FakeEngine : RepositoryGitEngine {
        var privateRepository = false
        var denyRepository = false
        var rejectToken = false
        var pushCalls = 0
        var synchronizeCalls = 0
        var lastArray: CharArray? = null
        val credentialsSeen = mutableListOf<String>()
        private fun record(token: CharArray) { lastArray = token; credentialsSeen += String(token) }
        private fun cannotRead(token: CharArray) = (privateRepository && token.isEmpty()) || denyRepository
        override fun prepare(repositoryDirectory: File, token: CharArray): RepositoryPreparationResult {
            record(token)
            return RepositoryPreparationResult(!cannotRead(token), "test", true,
                if (cannotRead(token)) "Access required" else "Prepared", authenticationRequired = cannotRead(token))
        }
        override fun refreshState(repositoryDirectory: File, token: CharArray): RepositoryStateSnapshot {
            record(token)
            return RepositoryStateSnapshot(
                if (cannotRead(token)) RepositoryStateType.ERROR else RepositoryStateType.SYNCHRONIZED,
                if (cannotRead(token)) CommitRelation.UNDETERMINED else CommitRelation.SYNCHRONIZED,
                "Safe status", "main", "head", "head", 0, 0, WorkingTreeChanges(), Instant.EPOCH,
                !cannotRead(token), authenticationRequired = cannotRead(token),
            )
        }
        override fun downloadFastForward(repositoryDirectory: File, token: CharArray): DownloadResult {
            record(token)
            return DownloadResult(DownloadOutcome.ALREADY_SYNCHRONIZED, "Safe", null, null, 0, true,
                refreshStateWithoutRecord(token))
        }
        private fun refreshStateWithoutRecord(token: CharArray) = RepositoryStateSnapshot(
            RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED, "Safe", "main", "head", "head",
            0, 0, WorkingTreeChanges(), Instant.EPOCH, true,
        )
        override fun uploadSafely(repositoryDirectory: File, token: CharArray, commitMessage: String): UploadResult {
            pushCalls++
            record(token)
            return UploadResult(if (rejectToken) UploadOutcome.AUTH_FAILED else UploadOutcome.SUCCESS,
                "Safe", null, null, false, 0, null, authenticationRejected = rejectToken)
        }
        override fun synchronizeSafely(repositoryDirectory: File, token: CharArray, commitMessage: String): SynchronizationResult {
            synchronizeCalls++
            record(token)
            return SynchronizationResult(SynchronizationOutcome.NOTHING_TO_DO, "Safe", null)
        }
    }
}
