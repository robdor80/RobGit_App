package es.robertodorado.robgit

/** Informational tasks only. None of these values represents a Git action. */
internal enum class AiTask(val label: String, val requiresAnalyzedState: Boolean) {
    EXPLAIN_STATUS("EXPLICAR ESTADO", true),
    SUMMARIZE_CHANGES("RESUMIR CAMBIOS", true),
    SUGGEST_COMMIT_MESSAGE("PROPONER MENSAJE DE COMMIT", true),
    EXPLAIN_BLOCK("EXPLICAR BLOQUEO O CONFLICTO", true),
    CUSTOM_QUESTION("PREGUNTA PERSONALIZADA", false),
}

internal data class AiHumanStatus(
    val title: String,
    val explanation: String,
    val recommendation: String? = null,
    val blocked: Boolean = false,
)

/** Values copied from RobGit's snapshot; the AI cannot recalculate them. */
internal data class AiGitStatus(
    val state: String,
    val relation: String,
    val ahead: Int,
    val behind: Int,
    val diverged: Boolean,
    val hasConflicts: Boolean,
    val remoteStateIsFresh: Boolean,
    val checkedAt: String,
    val authenticationRequired: Boolean,
    val authenticationRejected: Boolean,
    val repositoryAccessDenied: Boolean,
)

internal data class AiFileChanges(
    val modified: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
    val staged: List<String> = emptyList(),
    val conflicting: List<String> = emptyList(),
)

internal data class AiLastOperation(
    val name: String,
    val outcome: String,
    val message: String,
    val error: String? = null,
)

/**
 * A bounded metadata snapshot, never a repository handle or file content.
 * URLs, local workspace paths, Git objects and credentials have no fields here.
 */
internal data class AiContext(
    val repositoryName: String?,
    val branch: String?,
    val humanStatus: AiHumanStatus,
    val gitStatus: AiGitStatus? = null,
    val changes: AiFileChanges = AiFileChanges(),
    val lastOperation: AiLastOperation? = null,
    val message: String? = null,
    val error: String? = null,
    val omittedFileCount: Int = 0,
)

internal data class AiRequest(
    val task: AiTask,
    val context: AiContext,
    val question: String? = null,
)

internal enum class AiResponseSource { DEVELOPMENT, PROVIDER }

/** Text is displayed for the user to read; it never carries executable actions. */
internal data class AiResponse(val text: String, val source: AiResponseSource)

internal interface AiProvider {
    suspend fun complete(request: AiRequest): AiResponse
}

internal sealed interface AiAssistantResult {
    data class Completed(val response: AiResponse) : AiAssistantResult
    data class Unavailable(val message: String) : AiAssistantResult
    data class Failed(val message: String) : AiAssistantResult
}
