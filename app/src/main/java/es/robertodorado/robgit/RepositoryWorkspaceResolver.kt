package es.robertodorado.robgit

import java.io.File
import java.nio.file.Files

class WorkspaceAccessException(message: String) : IllegalStateException(message)

/** Resolves one configured repository from trusted roots; no caller supplied absolute paths. */
class RepositoryWorkspaceResolver(
    private val privateRepositoriesRoot: File,
    private val documentsDirectory: File,
    private val hasAllFilesAccess: () -> Boolean,
) {
    fun requireSharedAccess() {
        if (!hasAllFilesAccess()) {
            throw WorkspaceAccessException("RobGit no puede acceder al workspace compartido de este repositorio.")
        }
    }

    fun rootFor(location: WorkspaceLocation): File = when (location) {
        WorkspaceLocation.APP_PRIVATE -> privateRepositoriesRoot.canonicalFile
        WorkspaceLocation.SHARED_DOCUMENTS -> resolveRobGitDocumentsRoot(documentsDirectory)
    }

    fun resolve(config: RepositoryConfig): File {
        if (config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS) requireSharedAccess()
        val validName = when (config.workspaceLocation) {
            WorkspaceLocation.APP_PRIVATE ->
                config.id == "legacy-robgit-pruebas" && config.localDirectoryName == "robgit-pruebas" ||
                    config.id.matches(Regex("[a-f0-9]{32}")) && config.localDirectoryName == "repo-${config.id}"
            WorkspaceLocation.SHARED_DOCUMENTS -> (12..64).any {
                config.localDirectoryName == sharedDirectoryName(config.displayName, config.id, it)
            }
        }
        require(validName) { "Directorio de workspace no permitido." }
        val root = rootFor(config.workspaceLocation)
        require(root.listFiles().orEmpty().none {
            it.name.equals(config.localDirectoryName, ignoreCase = true) && it.name != config.localDirectoryName
        }) { "Ya existe una carpeta de workspace con el mismo nombre y otras mayúsculas." }
        val child = File(root, config.localDirectoryName)
        require(!Files.isSymbolicLink(child.toPath())) { "El directorio del repositorio no puede ser un enlace simbólico." }
        val canonical = child.canonicalFile
        require(canonical.parentFile == root && canonical.name == config.localDirectoryName) {
            "El repositorio no puede salir de su workspace."
        }
        return canonical
    }
}
