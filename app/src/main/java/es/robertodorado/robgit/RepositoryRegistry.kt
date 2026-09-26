package es.robertodorado.robgit

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class RepositoryConfig(
    val id: String,
    val displayName: String,
    val remoteUrl: String,
    val branch: String,
    val localDirectoryName: String,
)

data class RepositoryCatalog(
    val repositories: List<RepositoryConfig>,
    val selectedRepositoryId: String?,
) {
    val selected: RepositoryConfig? get() = repositories.firstOrNull { it.id == selectedRepositoryId }
}

/** Only repository metadata is stored here. Credentials never enter this model or file. */
class RepositoryRegistry(private val file: File, private val repositoriesRoot: File) {
    private val directoryPattern = Regex("repo-[a-f0-9]{32}")
    private val branchPattern = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")
    private val namePattern = Regex("[A-Za-z0-9_.-]+")

    @Synchronized fun load(): RepositoryCatalog {
        if (!file.exists()) {
            val legacy = RepositoryConfig(
                id = "legacy-robgit-pruebas",
                displayName = "Robgit.pruebas",
                remoteUrl = "https://github.com/robdor80/Robgit.pruebas.git",
                branch = "main",
                localDirectoryName = "robgit-pruebas",
            )
            return RepositoryCatalog(listOf(legacy), legacy.id).also(::save)
        }
        val properties = Properties().apply { file.inputStream().use(::load) }
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: error("El registro de repositorios no es válido.")
        require(count in 0..1000) { "El registro de repositorios no es válido." }
        val repositories = (0 until count).map { index ->
            RepositoryConfig(
                id = requireNotNull(properties.getProperty("$index.id")),
                displayName = requireNotNull(properties.getProperty("$index.name")),
                remoteUrl = requireNotNull(properties.getProperty("$index.url")),
                branch = requireNotNull(properties.getProperty("$index.branch")),
                localDirectoryName = requireNotNull(properties.getProperty("$index.directory")),
            ).also(::validateStored)
        }
        require(repositories.map { it.id }.toSet().size == repositories.size &&
            repositories.map { it.localDirectoryName }.toSet().size == repositories.size &&
            repositories.map { it.remoteUrl.lowercase() to it.branch }.toSet().size == repositories.size
        ) { "El registro contiene repositorios duplicados." }
        val selectedId = properties.getProperty("selected")?.takeIf { selected ->
            repositories.any { it.id == selected }
        } ?: repositories.firstOrNull()?.id
        return RepositoryCatalog(repositories, selectedId)
    }

    @Synchronized fun add(name: String, url: String, branch: String): RepositoryCatalog {
        val current = load()
        val displayName = name.trim()
        require(displayName.isNotEmpty()) { "Introduce un nombre para el repositorio." }
        val remoteUrl = normalizeGitHubUrl(url)
        val normalizedBranch = validateBranch(branch)
        require(current.repositories.none { it.remoteUrl.equals(remoteUrl, ignoreCase = true) && it.branch == normalizedBranch }) {
            "Este repositorio ya está configurado en RobGit."
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val config = RepositoryConfig(id, displayName, remoteUrl, normalizedBranch, "repo-$id")
        return RepositoryCatalog(current.repositories + config, config.id).also(::save)
    }

    @Synchronized fun select(id: String): RepositoryCatalog {
        val current = load()
        require(current.repositories.any { it.id == id }) { "Repositorio desconocido." }
        return current.copy(selectedRepositoryId = id).also(::save)
    }

    @Synchronized fun remove(id: String): RepositoryCatalog {
        val current = load()
        val remaining = current.repositories.filterNot { it.id == id }
        require(remaining.size != current.repositories.size) { "Repositorio desconocido." }
        val selected = if (current.selectedRepositoryId == id) remaining.firstOrNull()?.id
            else current.selectedRepositoryId
        return RepositoryCatalog(remaining, selected).also(::save)
    }

    fun directoryFor(config: RepositoryConfig): File {
        validateStored(config)
        val root = repositoriesRoot.canonicalFile
        val child = File(root, config.localDirectoryName).canonicalFile
        require(child.parentFile == root) { "Directorio local no permitido." }
        return child
    }

    private fun validateStored(config: RepositoryConfig) {
        require(config.displayName.isNotBlank()) { "Nombre de repositorio no válido." }
        require(normalizeGitHubUrl(config.remoteUrl) == config.remoteUrl) { "URL guardada no válida." }
        require(validateBranch(config.branch) == config.branch) { "Rama guardada no válida." }
        require(config.localDirectoryName == "robgit-pruebas" && config.id == "legacy-robgit-pruebas" ||
            config.localDirectoryName.matches(directoryPattern) && config.id.matches(Regex("[a-f0-9]{32}")) &&
                config.localDirectoryName == "repo-${config.id}") { "Directorio local no permitido." }
    }

    private fun save(catalog: RepositoryCatalog) {
        file.parentFile?.mkdirs()
        val values = Properties().apply {
            setProperty("count", catalog.repositories.size.toString())
            catalog.selectedRepositoryId?.let { setProperty("selected", it) }
            catalog.repositories.forEachIndexed { index, config ->
                setProperty("$index.id", config.id)
                setProperty("$index.name", config.displayName)
                setProperty("$index.url", config.remoteUrl)
                setProperty("$index.branch", config.branch)
                setProperty("$index.directory", config.localDirectoryName)
            }
        }
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { values.store(it, "RobGit repositories; no credentials") }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateBranch(value: String): String {
        val branch = value.trim()
        require(branch.matches(branchPattern) && !branch.contains("..") && !branch.contains("//") &&
            !branch.endsWith(".") && !branch.endsWith("/") && !branch.endsWith(".lock") &&
            !branch.contains("@{") && !branch.startsWith("-")) { "Introduce una rama válida." }
        return branch
    }

    private fun normalizeGitHubUrl(value: String): String {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Introduce una URL HTTPS válida de GitHub."
        }
        val path = uri.path.orEmpty().removeSuffix("/")
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.size == 2) { "La URL debe contener usuario y repositorio." }
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        require(owner.matches(namePattern) && repo.matches(namePattern) && owner != "." && owner != ".." &&
            repo != "." && repo != "..") { "La URL de GitHub no es válida." }
        return "https://github.com/$owner/$repo.git"
    }
}
