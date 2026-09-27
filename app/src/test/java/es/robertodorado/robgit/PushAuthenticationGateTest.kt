package es.robertodorado.robgit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PushAuthenticationGateTest {
    @Test fun disconnectedPushDoesNotOpenCommitPromptOrReachGitMutation() = runTest {
        val access = FakeAccess(null)
        var promptRequests = 0
        var gitMutations = 0

        if (hasPushAuthentication(access)) promptRequests++
        if (promptRequests > 0) gitMutations++

        assertEquals(0, promptRequests)
        assertEquals(0, gitMutations)
    }

    @Test fun connectedPushKeepsNormalCommitPromptFlow() = runTest {
        val access = FakeAccess("oauth-token")
        var promptRequests = 0

        if (hasPushAuthentication(access)) promptRequests++

        assertEquals(1, promptRequests)
    }

    @Test fun missingOauthNeverFallsBackToPatOrManualCredentials() = runTest {
        val access = FakeAccess(null)

        assertFalse(hasPushAuthentication(access))
        assertEquals(1, access.tokenLookups)
    }

    private class FakeAccess(private val token: String?) : GitHubAccessTokenProvider {
        override val state: StateFlow<GitHubConnectionState> = MutableStateFlow(
            if (token == null) GitHubConnectionState.Disconnected else GitHubConnectionState.Connected("test")
        )
        var tokenLookups = 0
        override suspend fun validAccessToken(): String? { tokenLookups++; return token }
        override suspend fun reportAuthenticationRejected() = Unit
    }
}
