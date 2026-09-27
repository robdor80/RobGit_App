package es.robertodorado.robgit

import java.io.File

/** The production bridge from OAuth to the existing, safety-checked synchronous Git engine. */
internal class GitHubGitOperations(private val access: GitHubAccessTokenProvider) {
    private suspend fun <T> withCredentials(required: Boolean, action: suspend (CharArray, Boolean) -> T): T {
        val current = access.state.value
        val token = if (current == GitHubConnectionState.NeedsReauth ||
            current == GitHubConnectionState.Authorizing) null else access.validAccessToken()
        if (token == null && (required || current is GitHubConnectionState.Connected ||
                current == GitHubConnectionState.Refreshing ||
                (current as? GitHubConnectionState.Error)?.retryable == true)) {
            throw GitAccessUnavailableException(access.state.value)
        }
        val temporary = token?.toCharArray() ?: charArrayOf()
        try {
            return action(temporary, token != null)
        } finally {
            temporary.fill('\u0000')
        }
    }

    suspend fun prepare(service: RepositoryGitEngine, directory: File): RepositoryPreparationResult {
        if (directory.exists()) return service.prepare(directory, charArrayOf())
        return withCredentials(false) { token, authenticated ->
            val result = service.prepare(directory, token)
            if (authenticated && result.authenticationRejected) access.reportAuthenticationRejected()
            if (authenticated && result.authenticationRequired && !result.authenticationRejected)
                result.copy(message = "RobGit no tiene acceso a este repositorio desde GitHub.")
            else result
        }
    }

    suspend fun analyze(service: RepositoryGitEngine, directory: File): RepositoryStateSnapshot =
        withCredentials(false) { token, authenticated ->
            val result = service.refreshState(directory, token)
            if (authenticated && result.authenticationRejected) access.reportAuthenticationRejected()
            if (authenticated && result.authenticationRequired && !result.authenticationRejected)
                result.copy(repositoryAccessDenied = true) else result
        }

    suspend fun pull(service: RepositoryGitEngine, directory: File): DownloadResult =
        withCredentials(false) { token, authenticated ->
            val result = service.downloadFastForward(directory, token)
            if (authenticated && result.finalState?.authenticationRejected == true) access.reportAuthenticationRejected()
            if (authenticated && result.finalState?.authenticationRequired == true &&
                result.finalState.authenticationRejected.not())
                result.copy(finalState = result.finalState.copy(repositoryAccessDenied = true)) else result
        }

    suspend fun push(service: RepositoryGitEngine, directory: File, message: String): UploadResult =
        withCredentials(true) { token, _ ->
            service.uploadSafely(directory, token, message).also {
                if (it.authenticationRejected) access.reportAuthenticationRejected()
            }
        }

    suspend fun synchronize(service: RepositoryGitEngine, directory: File, message: String): SynchronizationResult =
        withCredentials(false) { token, authenticated ->
            val result = service.synchronizeSafely(directory, token, message)
            if (authenticated && (result.finalState?.authenticationRejected == true ||
                        result.uploadResult?.authenticationRejected == true)) access.reportAuthenticationRejected()
            if (authenticated && result.finalState?.authenticationRequired == true &&
                result.finalState.authenticationRejected.not())
                result.copy(finalState = result.finalState.copy(repositoryAccessDenied = true)) else result
        }

    suspend fun migrate(manager: RepositoryMigrationManager, repositoryId: String,
                        onAnalyzed: (RepositoryStateSnapshot) -> Unit,
                        progress: (MigrationPhase) -> Unit): MigrationRecord =
        withCredentials(false) { token, authenticated ->
            try {
                manager.migrate(repositoryId, token, onAnalyzed, progress)
            } catch (failure: MigrationAuthenticationRequiredException) {
                if (authenticated && failure.rejected) access.reportAuthenticationRejected()
                throw failure
            }
        }

    suspend fun sharedPrepare(probe: SharedWorkspaceProbe): SharedWorkspaceResult =
        withCredentials(false) { token, authenticated ->
            probe.prepare(token).also { if (authenticated && it.authenticationRejected) access.reportAuthenticationRejected() }
        }
    suspend fun sharedAnalyze(probe: SharedWorkspaceProbe): SharedWorkspaceResult =
        withCredentials(false) { token, authenticated ->
            probe.analyze(token).also { if (authenticated && it.authenticationRejected) access.reportAuthenticationRejected() }
        }
    suspend fun sharedPull(probe: SharedWorkspaceProbe): SharedWorkspaceResult =
        withCredentials(false) { token, authenticated ->
            probe.pull(token).also { if (authenticated && it.authenticationRejected) access.reportAuthenticationRejected() }
        }
    suspend fun sharedPush(probe: SharedWorkspaceProbe, message: String): SharedWorkspaceResult =
        withCredentials(true) { token, authenticated ->
            probe.push(token, message).also { if (authenticated && it.authenticationRejected) access.reportAuthenticationRejected() }
        }
    suspend fun sharedSynchronize(probe: SharedWorkspaceProbe, message: String): SharedWorkspaceResult =
        withCredentials(false) { token, authenticated ->
            probe.synchronize(token, message).also { if (authenticated && it.authenticationRejected) access.reportAuthenticationRejected() }
        }

    suspend fun authenticatedDiagnostic(service: GitRepositoryService, directory: File): DiagnosticResult =
        withCredentials(true) { token, _ ->
            service.runAuthenticatedPushDiagnostic(directory, token).also {
                if (it.authenticationRejected) access.reportAuthenticationRejected()
            }
        }

}

internal class GitAccessUnavailableException(state: GitHubConnectionState) : Exception(
    when (state) {
        GitHubConnectionState.NeedsReauth -> "Es necesario volver a conectar GitHub desde Ajustes."
        GitHubConnectionState.NotConfigured -> "La conexión con GitHub no está configurada localmente."
        is GitHubConnectionState.Error -> if (state.retryable)
            "No se pudo renovar la conexión con GitHub por un problema de red. Reinténtalo."
        else "Conecta RobGit con GitHub desde Ajustes para continuar."
        else -> "Conecta RobGit con GitHub desde Ajustes para continuar."
    },
)
