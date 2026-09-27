package es.robertodorado.robgit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedGitHubAuthStore(context: Context) : GitHubAuthStore {
    private val preferences = context.getSharedPreferences("github_oauth", Context.MODE_PRIVATE)
    private val alias = "robgit_github_oauth_aes"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8)
    }

    private fun read(name: String): JsonObject? {
        return try {
            val encoded = preferences.getString(name, null) ?: return null
            JsonParser.parseString(decrypt(encoded)).asJsonObject
        } catch (_: Exception) {
            preferences.edit().clear().commit()
            throw AuthStoreUnavailableException()
        }
    }

    private fun write(name: String, json: JsonObject) {
        try {
            val encrypted = encrypt(json.toString())
            if (!preferences.edit().putString(name, encrypted).commit()) throw AuthStoreUnavailableException()
        } catch (_: Exception) {
            throw AuthStoreUnavailableException()
        }
    }

    private fun remove(name: String) {
        if (!preferences.edit().remove(name).commit()) throw AuthStoreUnavailableException()
    }

    override fun readPending(): OAuthPending? = read("pending")?.let {
        try {
            OAuthPending(it.get("state").asString, it.get("verifier").asString, it.get("createdAt").asLong)
        } catch (_: Exception) {
            clearAll()
            throw AuthStoreUnavailableException()
        }
    }

    override fun writePending(pending: OAuthPending) {
        val json = JsonObject().apply {
            addProperty("state", pending.state)
            addProperty("verifier", pending.verifier)
            addProperty("createdAt", pending.createdAt)
        }
        write("pending", json)
    }

    override fun clearPending() = remove("pending")

    override fun readTokens(): OAuthTokens? = read("tokens")?.let {
        try {
            OAuthTokens(
                it.get("accessToken").asString, it.get("refreshToken").asString,
                it.get("accessExpiresAt").asLong, it.get("refreshExpiresAt").asLong,
                it.get("tokenType").asString, it.get("scope").asString, it.get("login").asString,
            )
        } catch (_: Exception) {
            clearAll()
            throw AuthStoreUnavailableException()
        }
    }

    override fun writeTokens(tokens: OAuthTokens) {
        val json = JsonObject().apply {
            addProperty("accessToken", tokens.accessToken)
            addProperty("refreshToken", tokens.refreshToken)
            addProperty("accessExpiresAt", tokens.accessExpiresAt)
            addProperty("refreshExpiresAt", tokens.refreshExpiresAt)
            addProperty("tokenType", tokens.tokenType)
            addProperty("scope", tokens.scope)
            addProperty("login", tokens.login)
        }
        // Both rotating tokens are encrypted and committed in one preference value.
        write("tokens", json)
    }

    override fun clearTokens() = remove("tokens")

    override fun clearAll() {
        if (!preferences.edit().clear().commit()) throw AuthStoreUnavailableException()
    }
}

internal class HttpGitHubOAuthClient(private val config: GitHubOAuthConfig) : GitHubOAuthClient {
    override suspend fun exchange(code: String, verifier: String): GitHubTokenResponse = postToken(
        listOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "code" to code,
            "redirect_uri" to GITHUB_CALLBACK,
            "code_verifier" to verifier,
        ),
    )

    override suspend fun refresh(refreshToken: String): GitHubTokenResponse = postToken(
        listOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ),
    )

    private suspend fun postToken(fields: List<Pair<String, String>>): GitHubTokenResponse = withContext(Dispatchers.IO) {
        val connection = (URL("https://github.com/login/oauth/access_token").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
            }.toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) classify(status)
            try {
                GitHubTokenParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                throw OAuthRejectedException()
            }
        } catch (_: IOException) {
            throw OAuthTemporaryException()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun login(accessToken: String): String = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/user").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            if (status !in 200..299) classify(status)
            try {
                JsonParser.parseString(connection.inputStream.bufferedReader().use { it.readText() })
                    .asJsonObject.get("login").asString.takeIf { it.isNotBlank() } ?: throw OAuthRejectedException()
            } catch (_: Exception) {
                throw OAuthRejectedException()
            }
        } catch (_: IOException) {
            throw OAuthTemporaryException()
        } finally {
            connection.disconnect()
        }
    }

    private fun classify(status: Int): Nothing = if (status == 429 || status >= 500) {
        throw OAuthTemporaryException()
    } else {
        throw OAuthRejectedException()
    }
}
