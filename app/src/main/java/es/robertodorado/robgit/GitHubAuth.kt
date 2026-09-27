package es.robertodorado.robgit

import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

internal const val GITHUB_CALLBACK = "https://robgit.robertodorado.es/oauth/github/callback"
private const val PENDING_LIFETIME_SECONDS = 600L
private const val ACCESS_EXPIRY_MARGIN_SECONDS = 300L

internal data class GitHubOAuthConfig(val clientId: String, val clientSecret: String) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

internal sealed interface GitHubConnectionState {
    data object NotConfigured : GitHubConnectionState
    data object Disconnected : GitHubConnectionState
    data object Authorizing : GitHubConnectionState
    data class Connected(val login: String) : GitHubConnectionState
    data object Refreshing : GitHubConnectionState
    data object NeedsReauth : GitHubConnectionState
    data class Error(val message: String, val retryable: Boolean = false) : GitHubConnectionState
}

// Deliberately not data classes: token and verifier values must not appear in generated toString().
internal class OAuthPending(val state: String, val verifier: String, val createdAt: Long)
internal class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val tokenType: String,
    val scope: String,
    val login: String,
)
internal class GitHubTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val refreshExpiresIn: Long,
    val tokenType: String,
    val scope: String,
) {
    fun credentials(now: Long, login: String): OAuthTokens = OAuthTokens(
        accessToken, refreshToken, Math.addExact(now, expiresIn),
        Math.addExact(now, refreshExpiresIn), tokenType, scope, login,
    )
}

internal object GitHubTokenParser {
    fun parse(json: String): GitHubTokenResponse {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.has("error")) throw OAuthRejectedException()
        fun required(name: String): String = root.get(name)?.asString?.takeIf { it.isNotBlank() }
            ?: throw OAuthRejectedException()
        fun seconds(name: String): Long = root.get(name)?.asLong?.takeIf { it > 0 }
            ?: throw OAuthRejectedException()
        val type = required("token_type")
        if (!type.equals("bearer", ignoreCase = true)) throw OAuthRejectedException()
        return GitHubTokenResponse(
            required("access_token"), required("refresh_token"), seconds("expires_in"),
            seconds("refresh_token_expires_in"), type, root.get("scope")?.asString.orEmpty(),
        )
    }
}

internal object GitHubPkce {
    private val random = SecureRandom()

    fun freshState(): String = randomUrlString(32)
    fun freshVerifier(): String = randomUrlString(32)
    private fun randomUrlString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
    )
}

internal sealed interface GitHubCallback {
    data class Code(val code: String, val state: String) : GitHubCallback
    data class Denied(val state: String) : GitHubCallback
    data object Invalid : GitHubCallback
}

internal object GitHubCallbackParser {
    fun parse(raw: String): GitHubCallback {
        return try {
        val uri = URI(raw)
        if (uri.scheme != "https" || !uri.host.equals("robgit.robertodorado.es", true) ||
            uri.rawPath != "/oauth/github/callback" || uri.port != -1 || uri.rawUserInfo != null ||
            uri.rawFragment != null
        ) GitHubCallback.Invalid else {
            val values = mutableMapOf<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
                val key = URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                if (values.put(key, value) != null) return GitHubCallback.Invalid
            }
            val state = values["state"].orEmpty()
            when {
                state.isBlank() -> GitHubCallback.Invalid
                values.containsKey("error") -> GitHubCallback.Denied(state)
                values["code"].isNullOrBlank() -> GitHubCallback.Invalid
                else -> GitHubCallback.Code(values.getValue("code"), state)
            }
        }
        } catch (_: Exception) {
            GitHubCallback.Invalid
        }
    }
}

internal interface GitHubAuthStore {
    fun readPending(): OAuthPending?
    fun writePending(pending: OAuthPending)
    fun clearPending()
    fun readTokens(): OAuthTokens?
    fun writeTokens(tokens: OAuthTokens)
    fun clearTokens()
    fun clearAll()
}

internal class AuthStoreUnavailableException : Exception()
internal class OAuthRejectedException : Exception()
internal class OAuthTemporaryException : Exception()

internal interface GitHubOAuthClient {
    suspend fun exchange(code: String, verifier: String): GitHubTokenResponse
    suspend fun refresh(refreshToken: String): GitHubTokenResponse
    suspend fun login(accessToken: String): String
}

internal interface GitHubAccessTokenProvider {
    val state: StateFlow<GitHubConnectionState>
    suspend fun validAccessToken(): String?
    suspend fun reportAuthenticationRejected()
}

internal class GitHubAuthService(
    private val config: GitHubOAuthConfig,
    private val store: GitHubAuthStore,
    private val client: GitHubOAuthClient,
    private val clock: Clock = Clock.systemUTC(),
) : GitHubAccessTokenProvider {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<GitHubConnectionState>(
        if (config.configured) GitHubConnectionState.Disconnected else GitHubConnectionState.NotConfigured,
    )
    override val state: StateFlow<GitHubConnectionState> = mutableState
    private var rejectedSession = false
    private fun now(): Long = clock.instant().epochSecond

    suspend fun restore() = mutex.withLock {
        if (rejectedSession) {
            mutableState.value = GitHubConnectionState.NeedsReauth
            return@withLock
        }
        if (!config.configured) {
            mutableState.value = GitHubConnectionState.NotConfigured
            return@withLock
        }
        try {
            val tokens = store.readTokens()
            when {
                tokens != null -> validAccessTokenLocked(tokens)
                store.readPending()?.let { now() - it.createdAt < PENDING_LIFETIME_SECONDS } == true ->
                    mutableState.value = GitHubConnectionState.Authorizing
                else -> {
                    store.clearPending()
                    mutableState.value = GitHubConnectionState.Disconnected
                }
            }
        } catch (_: AuthStoreUnavailableException) {
            mutableState.value = GitHubConnectionState.NeedsReauth
        }
    }

    suspend fun beginAuthorization(): String? = mutex.withLock {
        if (!config.configured) {
            mutableState.value = GitHubConnectionState.NotConfigured
            return@withLock null
        }
        try {
            val state = GitHubPkce.freshState()
            val verifier = GitHubPkce.freshVerifier()
            store.clearAll()
            rejectedSession = false
            store.writePending(OAuthPending(state, verifier, now()))
            mutableState.value = GitHubConnectionState.Authorizing
            val query = listOf(
                "client_id" to config.clientId,
                "redirect_uri" to GITHUB_CALLBACK,
                "state" to state,
                "code_challenge" to GitHubPkce.challenge(verifier),
                "code_challenge_method" to "S256",
            ).joinToString("&") { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }
            "https://github.com/login/oauth/authorize?$query"
        } catch (_: Exception) {
            runCatching { store.clearAll() }
            mutableState.value = GitHubConnectionState.Error("No se pudo iniciar la conexión con GitHub.")
            null
        }
    }

    suspend fun cancelAuthorization() = mutex.withLock {
        store.clearPending()
        mutableState.value = GitHubConnectionState.Disconnected
    }

    suspend fun handleCallback(raw: String) = mutex.withLock {
        try {
            val pending = store.readPending()
            val callback = GitHubCallbackParser.parse(raw)
            // Consume before network I/O. A repeated callback can never reuse this verifier.
            store.clearPending()
            val callbackState = when (callback) {
                is GitHubCallback.Code -> callback.state
                is GitHubCallback.Denied -> callback.state
                GitHubCallback.Invalid -> null
            }
            if (pending == null || callbackState == null || now() - pending.createdAt !in 0 until PENDING_LIFETIME_SECONDS ||
                !MessageDigest.isEqual(pending.state.toByteArray(), callbackState.toByteArray())
            ) {
                mutableState.value = GitHubConnectionState.Error("La respuesta de GitHub no corresponde a esta conexión. Inténtalo de nuevo.")
                return@withLock
            }
            if (callback is GitHubCallback.Denied) {
                mutableState.value = GitHubConnectionState.Error("La conexión con GitHub se canceló o fue rechazada.")
                return@withLock
            }
            val code = (callback as GitHubCallback.Code).code
            val response = client.exchange(code, pending.verifier)
            val login = client.login(response.accessToken)
            if (login.isBlank()) throw OAuthRejectedException()
            store.writeTokens(response.credentials(now(), login))
            mutableState.value = GitHubConnectionState.Connected(login)
        } catch (_: OAuthTemporaryException) {
            mutableState.value = GitHubConnectionState.Error("No se pudo contactar con GitHub. Vuelve a conectar cuando haya red.")
        } catch (_: Exception) {
            runCatching { store.clearAll() }
            mutableState.value = GitHubConnectionState.Error("No se pudo completar la conexión con GitHub.")
        }
    }

    override suspend fun validAccessToken(): String? = mutex.withLock {
        if (rejectedSession || mutableState.value == GitHubConnectionState.NeedsReauth) return@withLock null
        if (!config.configured) {
            mutableState.value = GitHubConnectionState.NotConfigured
            return@withLock null
        }
        try {
            val tokens = store.readTokens()
            if (tokens == null) {
                mutableState.value = GitHubConnectionState.Disconnected
                null
            } else validAccessTokenLocked(tokens)
        } catch (_: AuthStoreUnavailableException) {
            mutableState.value = GitHubConnectionState.NeedsReauth
            null
        }
    }

    override suspend fun reportAuthenticationRejected() = mutex.withLock {
        rejectedSession = true
        runCatching { store.clearTokens() }
        mutableState.value = GitHubConnectionState.NeedsReauth
    }

    private suspend fun validAccessTokenLocked(tokens: OAuthTokens): String? {
        val time = now()
        if (tokens.accessExpiresAt - time > ACCESS_EXPIRY_MARGIN_SECONDS) {
            mutableState.value = GitHubConnectionState.Connected(tokens.login)
            return tokens.accessToken
        }
        if (tokens.refreshExpiresAt <= time) {
            store.clearTokens()
            rejectedSession = true
            mutableState.value = GitHubConnectionState.NeedsReauth
            return null
        }
        mutableState.value = GitHubConnectionState.Refreshing
        return try {
            val response = client.refresh(tokens.refreshToken)
            val rotated = response.credentials(now(), tokens.login)
            store.writeTokens(rotated)
            mutableState.value = GitHubConnectionState.Connected(rotated.login)
            rotated.accessToken
        } catch (_: OAuthTemporaryException) {
            mutableState.value = GitHubConnectionState.Error("No se pudo renovar la conexión por un problema de red. Reinténtalo.", retryable = true)
            null
        } catch (_: Exception) {
            runCatching { store.clearTokens() }
            rejectedSession = true
            mutableState.value = GitHubConnectionState.NeedsReauth
            null
        }
    }

    suspend fun disconnect() = mutex.withLock {
        try {
            store.clearAll()
            rejectedSession = false
            mutableState.value = if (config.configured) GitHubConnectionState.Disconnected else GitHubConnectionState.NotConfigured
        } catch (_: Exception) {
            mutableState.value = GitHubConnectionState.Error("No se pudo borrar la conexión local.")
        }
    }
}
