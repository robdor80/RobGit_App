package es.robertodorado.robgit

import java.util.Locale

/**
 * Explicit privacy boundary before any provider. H12A only supplies metadata.
 * External providers will need a separate, explicit policy for any file content.
 * Pattern redaction is defense in depth, not a detector for arbitrary secrets.
 */
internal class AiContextFilter {
    fun filter(context: AiContext): AiContext {
        val files = context.changes
        val rawPaths = (files.modified + files.added + files.deleted + files.untracked +
            files.staged + files.conflicting).distinct()
        val mapped = rawPaths.associateWith(::safePath)
        val allowed = mapped.values.filterNotNull().distinct().sorted().take(MAX_FILES).toSet()
        fun paths(values: List<String>) = values.mapNotNull { mapped[it] }
            .filter { it in allowed }.distinct().sorted()
        val omitted = mapped.values.count { it == null || it !in allowed }
        return context.copy(
            repositoryName = sanitizeText(context.repositoryName, 120),
            branch = sanitizeText(context.branch, 200),
            humanStatus = context.humanStatus.let {
                it.copy(title = sanitizeText(it.title).orEmpty(),
                    explanation = sanitizeText(it.explanation).orEmpty(),
                    recommendation = sanitizeText(it.recommendation))
            },
            gitStatus = context.gitStatus?.let {
                it.copy(state = sanitizeText(it.state, 80).orEmpty(),
                    relation = sanitizeText(it.relation, 80).orEmpty(),
                    checkedAt = sanitizeText(it.checkedAt, 80).orEmpty())
            },
            changes = AiFileChanges(paths(files.modified), paths(files.added), paths(files.deleted),
                paths(files.untracked), paths(files.staged), paths(files.conflicting)),
            lastOperation = context.lastOperation?.let {
                it.copy(name = sanitizeText(it.name, 100).orEmpty(),
                    outcome = sanitizeText(it.outcome, 100).orEmpty(),
                    message = sanitizeText(it.message).orEmpty(), error = sanitizeText(it.error))
            },
            message = sanitizeText(context.message),
            error = sanitizeText(context.error),
            omittedFileCount = context.omittedFileCount.coerceAtLeast(0) + omitted,
        )
    }

    fun sanitizeText(value: String?, maxCharacters: Int = MAX_TEXT): String? {
        if (value.isNullOrBlank()) return null
        // Redact before truncation so a long key is not leaked as a truncated fragment.
        val redacted = value
            .replace(PRIVATE_KEY, "[clave privada omitida]")
            .replace(AUTHORIZATION, "[credencial omitida]")
            .replace(CREDENTIAL_ASSIGNMENT, "[credencial omitida]")
            .replace(KNOWN_TOKEN, "[credencial omitida]")
            .replace(URL, "[enlace omitido]")
            .lineSequence().joinToString("\n") { line ->
                if (SENSITIVE_REFERENCE.containsMatchIn(line)) "[metadato sensible omitido]" else line
            }
            .filter { !it.isISOControl() || it == '\n' || it == '\t' }
            .trim()
        return redacted.take(maxCharacters).ifBlank { null }
    }

    private fun safePath(raw: String): String? {
        val path = raw.replace('\\', '/')
        if (path.isBlank() || path.length > MAX_PATH || path.startsWith('/') ||
            ':' in path || path.any { it.isISOControl() } || KNOWN_TOKEN.containsMatchIn(path) ||
            CREDENTIAL_ASSIGNMENT.containsMatchIn(path)) return null
        val parts = path.lowercase(Locale.ROOT).split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it in SENSITIVE_NAMES ||
                it.startsWith(".env") || it.startsWith("id_rsa") || it.startsWith("id_ed25519") ||
                it.startsWith("id_dsa") || it.startsWith("id_ecdsa") ||
                "secret" in it || "credential" in it ||
                SENSITIVE_EXTENSIONS.any { extension -> it.endsWith(extension) } }) return null
        return path
    }

    companion object {
        const val MAX_FILES = 100
        const val MAX_TEXT = 1_500
        const val MAX_QUESTION = 2_000
        private const val MAX_PATH = 240
        private val SENSITIVE_NAMES = setOf(
            ".git", ".ssh", ".aws", ".azure", ".kube", ".netrc", ".npmrc", ".pypirc",
            ".git-credentials", "local.properties", "gradle.properties", "google-services.json",
            "settings.xml", "nuget.config", "keystore.properties",
        )
        private val SENSITIVE_EXTENSIONS = listOf(".pem", ".key", ".jks", ".keystore", ".p12", ".pfx", ".kdbx")
        private val PRIVATE_KEY = Regex(
            """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"""
        )
        private val AUTHORIZATION = Regex("""(?im)\b(?:authorization\s*[:=]\s*|bearer\s+)[^\r\n]+""")
        private val CREDENTIAL_ASSIGNMENT = Regex(
            """(?im)\b[\w.-]*(?:token|secret|password|passwd|credential|api[_-]?key|private[_-]?key)\b["']?\s*[:=]\s*[^\r\n]+"""
        )
        private val KNOWN_TOKEN = Regex(
            """(?i)\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|sk-[A-Za-z0-9_-]{8,})\b"""
        )
        private val URL = Regex("""(?i)\b(?:https?|ssh|file)://\S+""")
        private val SENSITIVE_REFERENCE = Regex(
            """(?i)(?:\.git(?:[/\\]|\b)|local\.properties|gradle\.properties|\.env\b|\.netrc\b|\.npmrc\b|\.ssh[/\\]|(?:id_rsa|id_ed25519|id_dsa|id_ecdsa)|\b\S+\.(?:pem|key|jks|keystore|p12|pfx|kdbx)\b)"""
        )
    }
}
