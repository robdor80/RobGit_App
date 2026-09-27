package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class AiContextFilterTest {
    private val filter = AiContextFilter()

    @Test fun excludesGitCredentialsKeysAndSensitiveConfigurationFromEveryFileCategory() {
        val sensitive = listOf(".git/config", ".git", "LOCAL.PROPERTIES", "config/.env.production",
            ".ssh/id_ed25519", "release.keystore", "release.jks", "private.pem", "key.p12",
            "cert.pfx", "secrets/config.json", "credentials.json", "gradle.properties",
            ".aws/config", "google-services.json", ".npmrc", "settings.xml")
        val files = sensitive + listOf("src/Main.kt", "README.md", ".github/workflows/build.yml")
        val original = AiTestFixtures.context().copy(
            changes = AiFileChanges(files, files, files, files, files, files),
        )
        val safe = filter.filter(original)
        val expected = listOf(".github/workflows/build.yml", "README.md", "src/Main.kt")
        assertEquals(AiFileChanges(expected, expected, expected, expected, expected, expected), safe.changes)
        assertEquals(sensitive.size, safe.omittedFileCount)
        assertEquals(files, original.changes.modified)
        sensitive.forEach { assertFalse(it in safe.changes.modified) }
    }

    @Test fun rejectsAbsolutePathsTraversalAndControlCharacters() {
        val files = listOf("../secret.txt", "/tmp/source.txt", "C:\\workspace\\source.txt",
            "src/../source.txt", "src/\nsource.txt", "\\\\server\\share\\source.txt", "src\\Main.kt")
        val safe = filter.filter(AiTestFixtures.context().copy(changes = AiFileChanges(untracked = files)))
        assertEquals(listOf("src/Main.kt"), safe.changes.untracked)
        assertEquals(6, safe.omittedFileCount)
    }

    @Test fun redactsKnownSecretsUrlsAndSensitiveReferencesAcrossMetadata() {
        val raw = "gho_test_access_value\ngithub_pat_test_value\nsk-test_key_value\n" +
            "Bearer opaque-test-value\nROBGIT_GITHUB_CLIENT_SECRET=test-secret-value\n" +
            "refresh_token=test-refresh-value\nhttps://user:password@host.invalid/repository\n" +
            "local.properties contains private configuration\n.git/config contains private data"
        val context = AiTestFixtures.context().copy(
            repositoryName = raw, branch = raw,
            humanStatus = AiHumanStatus(raw, raw, raw),
            message = raw, error = raw, lastOperation = AiLastOperation(raw, raw, raw, raw),
        )
        val filtered = filter.filter(context).toString()
        listOf("gho_test_access_value", "github_pat_test_value", "sk-test_key_value",
            "opaque-test-value", "test-secret-value", "test-refresh-value", "host.invalid",
            "local.properties", ".git/config", "private configuration", "private data")
            .forEach { assertFalse("Leaked " + it, filtered.contains(it)) }
    }

    @Test fun removesCompleteAndIncompletePrivateKeyBlocksBeforeTruncating() {
        val complete = "-----BEGIN RSA PRIVATE KEY-----\nprivate-test-material\n-----END RSA PRIVATE KEY-----"
        val incomplete = "-----BEGIN PRIVATE KEY-----\n" + "private-test-material".repeat(200)
        assertFalse(filter.sanitizeText(complete)!!.contains("private-test-material"))
        assertFalse(filter.sanitizeText(incomplete, 40)!!.contains("private-test-material"))
        assertEquals("mensaje útil", filter.sanitizeText("  mensaje útil  "))
        assertNull(filter.sanitizeText("   "))
    }

    @Test fun limitsTotalDistinctPathsAndMetadataWithDeterministicOrdering() {
        val paths = (0..149).map { "src/file-" + it.toString().padStart(3, '0') + ".kt" }
        val context = AiTestFixtures.context().copy(changes = AiFileChanges(modified = paths.reversed(),
            staged = paths), message = "x".repeat(5_000))
        val safe = filter.filter(context)
        assertEquals(paths.take(AiContextFilter.MAX_FILES), safe.changes.modified)
        assertEquals(safe.changes.modified, safe.changes.staged)
        assertEquals(50, safe.omittedFileCount)
        assertEquals(AiContextFilter.MAX_TEXT, safe.message!!.length)
    }

    @Test fun filteringTwicePreservesTheOmissionCountAndContext() {
        val context = AiTestFixtures.context().copy(changes = AiFileChanges(modified = listOf("safe.txt", ".git/config")))
        val safe = filter.filter(context)
        assertEquals(safe, filter.filter(safe))
    }
}
