package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.util.UUID

const val SHARED_WORKSPACE_REMOTE = "https://github.com/robdor80/robgit.prueba2.git"
const val SHARED_WORKSPACE_BRANCH = "main"
const val SHARED_WORKSPACE_DIRECTORY = "robgit-workspace-test"

data class SharedWorkspaceResult(
    val operation: String,
    val success: Boolean,
    val message: String,
    val repositoryPath: String,
    val state: RepositoryStateSnapshot? = null,
    val outcome: String? = null,
    val authenticationRequired: Boolean = false,
)

/** Isolated diagnostic harness; it never reads or modifies RepositoryRegistry. */
class SharedWorkspaceProbe(
    private val documentsDirectory: File,
    private val hasAllFilesAccess: () -> Boolean,
    private val remoteUrl: String = SHARED_WORKSPACE_REMOTE,
    private val branch: String = SHARED_WORKSPACE_BRANCH,
    private val remoteGateway: RepositoryRemoteGateway? = null,
) {
    val rootDirectory: File get() = resolveRobGitDocumentsRoot(documentsDirectory)
    val repositoryDirectory: File get() = resolveWorkspaceChild(rootDirectory, SHARED_WORKSPACE_DIRECTORY)

    fun prepare(token: CharArray = charArrayOf()): SharedWorkspaceResult = guarded("PREPARAR WORKSPACE", token) { path ->
        val refreshToken = token.copyOf()
        try {
            val service = service()
            val prepared = service.prepare(path, token)
            if (!prepared.success) {
                SharedWorkspaceResult("PREPARAR WORKSPACE", false, prepared.message, path.absolutePath,
                    authenticationRequired = prepared.authenticationRequired)
            } else {
                val state = service.refreshState(path, refreshToken)
                SharedWorkspaceResult("PREPARAR WORKSPACE", true,
                    if (state.authenticationRequired) "Workspace preparado; se necesita autorización para consultar GitHub." else prepared.message,
                    path.absolutePath, state, authenticationRequired = state.authenticationRequired)
            }
        } finally {
            refreshToken.fill('\u0000')
        }
    }

    fun analyze(token: CharArray = charArrayOf()): SharedWorkspaceResult = guarded("ANALIZAR WORKSPACE", token) { path ->
        val state = service().refreshState(path, token)
        SharedWorkspaceResult("ANALIZAR WORKSPACE", state.type != RepositoryStateType.ERROR,
            state.message, path.absolutePath, state, state.type.name, state.authenticationRequired)
    }

    fun pull(token: CharArray = charArrayOf()): SharedWorkspaceResult = guarded("PULL DE PRUEBA", token) { path ->
        val result = service().downloadFastForward(path, token)
        SharedWorkspaceResult("PULL DE PRUEBA", result.outcome in setOf(DownloadOutcome.SUCCESS, DownloadOutcome.ALREADY_SYNCHRONIZED),
            result.message, path.absolutePath, result.finalState, result.outcome.name,
            result.finalState?.authenticationRequired == true)
    }

    fun push(token: CharArray, message: String): SharedWorkspaceResult = guarded("PUSH DE PRUEBA", token) { path ->
        val result = service().uploadSafely(path, token, message)
        SharedWorkspaceResult("PUSH DE PRUEBA", result.outcome in setOf(UploadOutcome.SUCCESS, UploadOutcome.NOTHING_TO_UPLOAD),
            result.message, path.absolutePath, result.finalState, result.outcome.name,
            result.outcome == UploadOutcome.AUTH_REQUIRED)
    }

    fun synchronize(token: CharArray = charArrayOf(), message: String = "Cambios desde RobGit (workspace de prueba)"):
        SharedWorkspaceResult = guarded("SINCRONIZAR WORKSPACE", token) { path ->
        val result = service().synchronizeSafely(path, token, message)
        SharedWorkspaceResult("SINCRONIZAR WORKSPACE",
            result.outcome in setOf(SynchronizationOutcome.NOTHING_TO_DO, SynchronizationOutcome.SUCCESS_DOWNLOADED,
                SynchronizationOutcome.SUCCESS_UPLOADED), result.message, path.absolutePath,
            result.finalState, result.outcome.name, result.outcome == SynchronizationOutcome.AUTH_REQUIRED)
    }

    /** Exercises local file operations only inside a uniquely named directory under this repo's .git. */
    fun probeFilesystem(): SharedWorkspaceResult = guarded("PRUEBA FILESYSTEM", charArrayOf()) { path ->
        val gitDirectory = File(path, ".git").canonicalFile
        check(gitDirectory.isDirectory) { "El workspace no está preparado." }
        val probe = File(gitDirectory, "robgit-workspace-probe-${UUID.randomUUID()}")
        check(probe.canonicalFile.parentFile == gitDirectory && probe.mkdir()) { "No se pudo crear el directorio temporal del probe." }
        try {
            val source = File(probe, "file-before.txt")
            check(source.createNewFile())
            source.writeText("RobGit shared filesystem probe")
            check(source.readText() == "RobGit shared filesystem probe")
            val renamed = File(probe, "file-after.txt")
            Files.move(source.toPath(), renamed.toPath())
            check(renamed.readText() == "RobGit shared filesystem probe")
            check(renamed.delete())

            val child = File(probe, "directory-before")
            check(child.mkdir())
            val moved = File(probe, "directory-after")
            Files.move(child.toPath(), moved.toPath())
            check(moved.isDirectory)
            SharedWorkspaceResult("PRUEBA FILESYSTEM", true,
                "Crear, escribir, leer, renombrar, borrar y Files.move completados dentro de .git.",
                path.absolutePath)
        } finally {
            if (probe.canonicalFile.parentFile == gitDirectory) {
                check(probe.deleteRecursively() || !probe.exists()) { "No se pudo limpiar el directorio temporal del probe." }
            }
        }
    }

    private fun service(): RepositoryStateService = RepositoryStateService(
        repositoryUrl = remoteUrl,
        branch = branch,
        remoteGateway = remoteGateway ?: JGitRepositoryRemoteGateway(),
    )

    private inline fun guarded(operation: String, token: CharArray, action: (File) -> SharedWorkspaceResult): SharedWorkspaceResult {
        try {
            if (!workspaceOperationAllowed(hasAllFilesAccess())) {
                return SharedWorkspaceResult(operation, false,
                    "El acceso al workspace compartido no está concedido.", safeRepositoryPath(),
                    authenticationRequired = false)
            }
            val root = rootDirectory.canonicalFile
            val repository = repositoryDirectory.canonicalFile
            require(repository.parentFile == root) { "La ruta del repositorio escapa de Documents/RobGit." }
            val gitEntry = File(repository, ".git")
            if (gitEntry.exists()) {
                val gitDirectory = gitEntry.canonicalFile
                require(gitDirectory.parentFile == repository && gitDirectory.name == ".git") {
                    "La ruta .git no puede salir del repositorio de prueba."
                }
            }
            return action(repository)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val detail = when (failure) {
                is SecurityException -> "Android ha denegado el acceso al almacenamiento compartido."
                is java.io.IOException -> "Error de entrada/salida en el workspace compartido."
                else -> "La operación no pudo completarse con seguridad."
            }
            return SharedWorkspaceResult(operation, false,
                "$detail (${failure.javaClass.simpleName}).", safeRepositoryPath())
        } finally {
            token.fill('\u0000')
        }
    }

    private fun safeRepositoryPath(): String = runCatching { repositoryDirectory.absolutePath }.getOrDefault("Documents/RobGit/$SHARED_WORKSPACE_DIRECTORY")
}

internal fun resolveRobGitDocumentsRoot(documentsDirectory: File): File {
    val documents = documentsDirectory.canonicalFile
    val candidate = File(documents, "RobGit")
    require(!Files.isSymbolicLink(candidate.toPath())) { "La raíz del workspace no puede ser un enlace simbólico." }
    val root = candidate.canonicalFile
    require(root.parentFile == documents && root.name == "RobGit") { "La raíz del workspace debe permanecer dentro de Documents." }
    return root
}

internal fun resolveWorkspaceChild(rootDirectory: File, directoryName: String): File {
    require(directoryName.matches(Regex("[A-Za-z0-9._-]+")) && directoryName != "." && directoryName != "..") {
        "Nombre de directorio de workspace no válido."
    }
    val root = rootDirectory.canonicalFile
    val child = File(root, directoryName).canonicalFile
    require(child.parentFile == root) { "El repositorio de prueba debe permanecer dentro de Documents/RobGit." }
    return child
}

internal fun workspaceOperationAllowed(hasAllFilesAccess: Boolean): Boolean = hasAllFilesAccess
