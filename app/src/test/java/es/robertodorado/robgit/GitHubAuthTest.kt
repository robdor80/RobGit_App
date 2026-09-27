package es.robertodorado.robgit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GitHubAuthTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(10_000), ZoneOffset.UTC)
    private val config = GitHubOAuthConfig("test-client", "test-secret")

    @Test fun pkceGeneratesFreshValidVerifierAndState() {
        val first = GitHubPkce.freshVerifier()
        val second = GitHubPkce.freshVerifier()
        assertNotEquals(first, second)
        assertTrue(first.length in 43..128)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(GitHubPkce.freshState(), GitHubPkce.freshState())
    }

    @Test fun pkceS256MatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GitHubPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun authorizationHasRequiredParametersAndPersistsPending() = runTest {
        val store = FakeStore()
        val url = service(store).beginAuthorization()!!
        val query = URI(url).rawQuery
        assertTrue(query.contains("client_id=test-client"))
        assertTrue(query.contains("redirect_uri=https%3A%2F%2Frobgit.robertodorado.es%2Foauth%2Fgithub%2Fcallback"))
        assertTrue(query.contains("code_challenge_method=S256"))
        assertTrue(query.contains("code_challenge="))
        assertNotNull(store.pending)
        assertEquals(10_000, store.pending!!.createdAt)
        assertEquals(GitHubConnectionState.Authorizing, serviceStateFromStart(store))
    }

    @Test fun validCallbackExchangesCodeAndStoresIdentity() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        val service = service(store, client)
        service.beginAuthorization()
        service.handleCallback(callback("code-one", store.pending!!.state))
        assertEquals(1, client.exchanges)
        assertEquals("robdor80", store.tokens!!.login)
        assertNull(store.pending)
        assertEquals(GitHubConnectionState.Connected("robdor80"), service.state.value)
        assertEquals(10_000 + 3600, store.tokens!!.accessExpiresAt)
        assertEquals(10_000 + 7200, store.tokens!!.refreshExpiresAt)
    }

    @Test fun wrongStateRejectsCodeAndConsumesTransaction() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        val service = service(store, client)
        service.beginAuthorization()
        service.handleCallback(callback("code-one", "wrong"))
        assertEquals(0, client.exchanges)
        assertNull(store.pending)
        assertNull(store.tokens)
        assertTrue(service.state.value is GitHubConnectionState.Error)
    }

    @Test fun missingCodeAndOAuthErrorAreRejected() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        val service = service(store, client)
        service.beginAuthorization()
        service.handleCallback("$GITHUB_CALLBACK?state=${store.pending!!.state}")
        assertEquals(0, client.exchanges)
        assertNull(store.pending)
        service.beginAuthorization()
        service.handleCallback("$GITHUB_CALLBACK?error=access_denied&error_description=sensitive&state=${store.pending!!.state}")
        assertEquals(0, client.exchanges)
        assertNull(store.pending)
        assertTrue((service.state.value as GitHubConnectionState.Error).message.contains("canceló"))
    }

    @Test fun callbackFromWrongHostOrPathIsRejected() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        val service = service(store, client)
        service.beginAuthorization()
        service.handleCallback("https://example.com/oauth/github/callback?code=bad&state=${store.pending!!.state}")
        assertEquals(0, client.exchanges)
        service.beginAuthorization()
        service.handleCallback("https://robgit.robertodorado.es/oauth/github/other?code=bad&state=${store.pending!!.state}")
        assertEquals(0, client.exchanges)
    }

    @Test fun callbackCannotBeReplayed() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        val service = service(store, client)
        service.beginAuthorization()
        val url = callback("code-one", store.pending!!.state)
        service.handleCallback(url)
        service.handleCallback(url)
        assertEquals(1, client.exchanges)
    }

    @Test fun pendingTransactionSurvivesServiceRecreation() = runTest {
        val store = FakeStore()
        val client = FakeClient()
        service(store, client).beginAuthorization()
        val savedState = store.pending!!.state
        val recreated = service(store, client)
        recreated.handleCallback(callback("code-after-restart", savedState))
        assertEquals(GitHubConnectionState.Connected("robdor80"), recreated.state.value)
        assertEquals(1, client.exchanges)
    }

    @Test fun expiredPendingTransactionCannotExchangeCode() = runTest {
        val store = FakeStore(pending = OAuthPending("old-state", "old-verifier", 9_000))
        val client = FakeClient()
        service(store, client).handleCallback(callback("code", "old-state"))
        assertEquals(0, client.exchanges)
        assertNull(store.pending)
    }

    @Test fun tokenResponseParsesAllFieldsAndCalculatesExpiration() {
        val parsed = GitHubTokenParser.parse("""{"access_token":"access","expires_in":3600,"refresh_token":"refresh","refresh_token_expires_in":7200,"token_type":"bearer","scope":"repo"}""")
        val tokens = parsed.credentials(10_000, "user")
        assertEquals("access", tokens.accessToken)
        assertEquals("refresh", tokens.refreshToken)
        assertEquals("bearer", tokens.tokenType)
        assertEquals("repo", tokens.scope)
        assertEquals(13_600, tokens.accessExpiresAt)
        assertEquals(17_200, tokens.refreshExpiresAt)
    }

    @Test fun validAccessTokenDoesNotRefresh() = runTest {
        val store = FakeStore(tokens = tokens(accessExpiry = 11_000))
        val client = FakeClient()
        assertEquals("old-access", service(store, client).validAccessToken())
        assertEquals(0, client.refreshes)
    }

    @Test fun nearExpiryAndExpiredAccessRefreshAndRotatePairAtomically() = runTest {
        for (expiry in listOf(10_299L, 9_999L)) {
            val store = FakeStore(tokens = tokens(accessExpiry = expiry))
            val client = FakeClient()
            val service = service(store, client)
            assertEquals("new-access", service.validAccessToken())
            assertEquals(1, client.refreshes)
            assertEquals(1, store.tokenWrites.size)
            assertEquals("new-access", store.tokenWrites.single().accessToken)
            assertEquals("new-refresh", store.tokenWrites.single().refreshToken)
            assertEquals(GitHubConnectionState.Connected("robdor80"), service.state.value)
        }
    }

    @Test fun expiredRefreshNeedsReauthorization() = runTest {
        val store = FakeStore(tokens = tokens(accessExpiry = 9_000, refreshExpiry = 10_000))
        val client = FakeClient()
        val service = service(store, client)
        assertNull(service.validAccessToken())
        assertNull(store.tokens)
        assertEquals(0, client.refreshes)
        assertEquals(GitHubConnectionState.NeedsReauth, service.state.value)
    }

    @Test fun temporaryRefreshFailureKeepsStoredPair() = runTest {
        val original = tokens(accessExpiry = 10_100)
        val store = FakeStore(tokens = original)
        val client = FakeClient().apply { refreshFailure = OAuthTemporaryException() }
        val service = service(store, client)
        assertNull(service.validAccessToken())
        assertSame(original, store.tokens)
        assertTrue(service.state.value is GitHubConnectionState.Error)
    }

    @Test fun definitiveRefreshRejectionNeedsReauth() = runTest {
        val store = FakeStore(tokens = tokens(accessExpiry = 10_100))
        val client = FakeClient().apply { refreshFailure = OAuthRejectedException() }
        val service = service(store, client)
        assertNull(service.validAccessToken())
        assertNull(store.tokens)
        assertEquals(GitHubConnectionState.NeedsReauth, service.state.value)
    }

    @Test fun disconnectClearsTokensAndPending() = runTest {
        val store = FakeStore(tokens = tokens(), pending = OAuthPending("state", "verifier", 10_000))
        val service = service(store)
        service.disconnect()
        assertNull(store.tokens)
        assertNull(store.pending)
        assertEquals(GitHubConnectionState.Disconnected, service.state.value)
    }

    @Test fun invalidKeystoreChangesStateToNeedsReauth() = runTest {
        val store = FakeStore().apply { unavailable = true }
        val service = service(store)
        assertNull(service.validAccessToken())
        assertEquals(GitHubConnectionState.NeedsReauth, service.state.value)
    }

    @Test fun explicitGitAuthenticationRejectionClearsTokensAndCannotReuseSession() = runTest {
        val store = FakeStore(tokens = tokens())
        val service = service(store)
        service.reportAuthenticationRejected()
        assertNull(store.tokens)
        assertEquals(GitHubConnectionState.NeedsReauth, service.state.value)
        assertNull(service.validAccessToken())
        assertEquals(GitHubConnectionState.NeedsReauth, service.state.value)
    }

    @Test fun missingLocalConfigurationDisablesAuthorization() = runTest {
        val service = GitHubAuthService(GitHubOAuthConfig("", ""), FakeStore(), FakeClient(), clock)
        assertNull(service.beginAuthorization())
        assertEquals(GitHubConnectionState.NotConfigured, service.state.value)
    }

    @Test fun concurrentRequestsShareOneRefresh() = runTest {
        val store = FakeStore(tokens = tokens(accessExpiry = 10_100))
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply { beforeRefresh = { gate.await() } }
        val service = service(store, client)
        val first = async { service.validAccessToken() }
        yield()
        val second = async { service.validAccessToken() }
        yield()
        assertEquals(1, client.refreshes)
        gate.complete(Unit)
        assertEquals("new-access", first.await())
        assertEquals("new-access", second.await())
        assertEquals(1, client.refreshes)
    }

    private suspend fun serviceStateFromStart(store: FakeStore): GitHubConnectionState {
        val service = service(store)
        service.restore()
        return service.state.value
    }

    private fun callback(code: String, state: String) = "$GITHUB_CALLBACK?code=$code&state=$state"
    private fun service(store: FakeStore, client: FakeClient = FakeClient()) = GitHubAuthService(config, store, client, clock)
    private fun tokens(accessExpiry: Long = 13_600, refreshExpiry: Long = 17_200) = OAuthTokens(
        "old-access", "old-refresh", accessExpiry, refreshExpiry, "bearer", "repo", "robdor80",
    )

    private class FakeStore(
        var tokens: OAuthTokens? = null,
        var pending: OAuthPending? = null,
    ) : GitHubAuthStore {
        var unavailable = false
        val tokenWrites = mutableListOf<OAuthTokens>()
        override fun readPending(): OAuthPending? = pending
        override fun writePending(pending: OAuthPending) { this.pending = pending }
        override fun clearPending() { pending = null }
        override fun readTokens(): OAuthTokens? = if (unavailable) throw AuthStoreUnavailableException() else tokens
        override fun writeTokens(tokens: OAuthTokens) { tokenWrites.add(tokens); this.tokens = tokens }
        override fun clearTokens() { tokens = null }
        override fun clearAll() { tokens = null; pending = null }
    }

    private class FakeClient : GitHubOAuthClient {
        var exchanges = 0
        var refreshes = 0
        var refreshFailure: Exception? = null
        var beforeRefresh: suspend () -> Unit = {}
        override suspend fun exchange(code: String, verifier: String): GitHubTokenResponse {
            exchanges++
            return response()
        }
        override suspend fun refresh(refreshToken: String): GitHubTokenResponse {
            refreshes++
            beforeRefresh()
            refreshFailure?.let { throw it }
            return response()
        }
        override suspend fun login(accessToken: String): String = "robdor80"
        private fun response() = GitHubTokenResponse("new-access", "new-refresh", 3600, 7200, "bearer", "repo")
    }
}
