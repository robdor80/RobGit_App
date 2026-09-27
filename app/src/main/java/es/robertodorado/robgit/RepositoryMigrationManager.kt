package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

enum class MigrationPhase {
    PLANNED, COPYING, VERIFYING_TEMP, FINALIZING, VERIFYING_FINAL, SWITCHING_REGISTRY, COMPLETED,
}

data class MigrationRecord(
    val repositoryId: String,
    val sourceDirectory: String,
    val temporaryDirectory: String,
    val finalDirectory: String,
    val phase: MigrationPhase,
    val sourceFingerprint: String? = null,
)

class MigrationAuthenticationRequiredException(val rejected: Boolean = false) :
    IllegalStateException("Conecta RobGit con GitHub desde Ajustes para completar la migración.")

/** Copies an app-private repository without ever changing or deleting the source. */
class RepositoryMigrationManager(
    private val registry: RepositoryRegistry,
    private val resolver: RepositoryWorkspaceResolver,
    private val journalDirectory: File,
    private val remoteGateway: RepositoryRemoteGateway = JGitRepositoryRemoteGateway(),
) {
    @Synchronized fun pending(): List<MigrationRecord> {
        if (!journalDirectory.exists()) return emptyList()
        check(journalDirectory.isDirectory) { "El journal de migraciones no es un directorio." }
        val catalog = registry.load()
        return journalDirectory.listFiles().orEmpty().filter { it.name.endsWith(".properties") }
            .map(::read).filter { it.phase != MigrationPhase.COMPLETED }.onEach { record ->
                val config = catalog.repositories.firstOrNull { it.id == record.repositoryId }
                    ?: error("Hay una migración incompleta de un repositorio desconocido.")
                validateRecord(record, config)
            }
    }

    @Synchronized fun receipt(repositoryId: String): MigrationRecord? =
        journalFile(repositoryId).takeIf { it.exists() }?.let(::read)?.takeIf { it.phase == MigrationPhase.COMPLETED }

    @Synchronized fun migrate(
        repositoryId: String,
        token: CharArray = charArrayOf(),
        onAnalyzed: (RepositoryStateSnapshot) -> Unit = {},
        progress: (MigrationPhase) -> Unit = {},
    ): MigrationRecord {
        check(journalDirectory.isDirectory || journalDirectory.mkdirs()) { "No se pudo crear el journal de migraciones." }
        return RandomAccessFile(File(journalDirectory, "migration.lock"), "rw").use { channelFile ->
            val lock = try { channelFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "Ya hay una migración en curso. Espera a que termine." }
            lock.use { migrateLocked(repositoryId, token, onAnalyzed, progress) }
        }
    }

    private fun migrateLocked(repositoryId: String, token: CharArray,
        onAnalyzed: (RepositoryStateSnapshot) -> Unit, progress: (MigrationPhase) -> Unit): MigrationRecord {
        val config = registry.load().repositories.firstOrNull { it.id == repositoryId }
            ?: error("El repositorio ya no está configurado.")
        val journal = journalFile(repositoryId)
        var record = if (journal.exists()) read(journal) else {
            check(config.workspaceLocation == WorkspaceLocation.APP_PRIVATE) { "Este repositorio ya utiliza Documents/RobGit." }
            val finalName = (12..64).asSequence().map { sharedDirectoryName(config.displayName, config.id, it) }
                .first { candidate -> registry.load().repositories.none {
                    it.id != repositoryId && it.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS &&
                        it.localDirectoryName.equals(candidate, ignoreCase = true)
                } }
            val final = resolveShared(finalName)
            check(!final.exists()) { "La carpeta de destino ya existe. No se sobrescribirá." }
            val privateSource = resolver.resolve(config)
            check(privateSource.isDirectory && File(privateSource, ".git").isDirectory) {
                "El repositorio privado original no está preparado."
            }
            val planned = MigrationRecord(repositoryId, config.localDirectoryName,
                ".$finalName.migrating-$repositoryId", finalName, MigrationPhase.PLANNED)
            check(!resolveShared(planned.temporaryDirectory).exists()) { "El temporal de migración ya existe." }
            write(planned)
            progress(planned.phase)
            planned
        }
        validateRecord(record, config)
        if (record.phase == MigrationPhase.COMPLETED) {
            check(config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS &&
                config.localDirectoryName == record.finalDirectory) { "El recibo y el registro no coinciden." }
            return record
        }
        check(config.workspaceLocation == WorkspaceLocation.APP_PRIVATE && config.localDirectoryName == record.sourceDirectory ||
            config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS && config.localDirectoryName == record.finalDirectory) {
            "La configuración cambió durante la migración. No se tocará ninguna copia."
        }
        val privateConfig = config.copy(localDirectoryName = record.sourceDirectory, workspaceLocation = WorkspaceLocation.APP_PRIVATE)
        val source = resolver.resolve(privateConfig)
        check(source.isDirectory && File(source, ".git").isDirectory) { "La copia privada original no está disponible." }
        val temporary = resolveShared(record.temporaryDirectory)
        val final = resolveShared(record.finalDirectory)
        if (config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS) {
            check(record.phase == MigrationPhase.SWITCHING_REGISTRY && final.isDirectory && !temporary.exists()) {
                "El registro y el journal no coinciden. No se utilizará una copia dudosa."
            }
            return completeActivated(config, record, final, token, onAnalyzed, progress)
        }
        check(!final.exists() || record.phase in setOf(MigrationPhase.FINALIZING, MigrationPhase.VERIFYING_FINAL,
            MigrationPhase.SWITCHING_REGISTRY)) { "La carpeta final apareció antes de finalizar la copia. No se sobrescribirá." }
        val sourceManifest = scan(source)
        val sourceGit = gitSnapshot(source, config.branch)
        check(sourceGit.origin == config.remoteUrl && sourceGit.branch == config.branch &&
            sourceGit.head != null && sourceGit.remoteHead != null &&
            sourceGit.trackingRemote == "origin" && sourceGit.trackingMerge == "refs/heads/${config.branch}") {
            "El repositorio privado no coincide con su URL, rama o tracking configurados."
        }
        check(scan(source) == sourceManifest) { "El origen privado cambió durante la comprobación Git." }
        val fingerprint = fingerprint(sourceManifest)
        if (record.sourceFingerprint == null) {
            check(config.workspaceLocation == WorkspaceLocation.APP_PRIVATE && record.phase == MigrationPhase.PLANNED)
            record = record.copy(phase = MigrationPhase.COPYING, sourceFingerprint = fingerprint).also(::write)
            progress(record.phase)
        } else {
            check(record.sourceFingerprint == fingerprint) { "El origen privado cambió durante la migración. Se ha detenido sin activar la copia." }
        }

        if (config.workspaceLocation == WorkspaceLocation.APP_PRIVATE && !final.exists()) {
            check(record.phase in setOf(MigrationPhase.COPYING, MigrationPhase.VERIFYING_TEMP, MigrationPhase.FINALIZING)) {
                "El destino final no existe en la fase guardada."
            }
            if (record.phase == MigrationPhase.FINALIZING) {
                check(temporary.exists()) { "No se encuentra el temporal ni el destino final." }
            } else {
                if (!temporary.exists()) check(temporary.mkdir()) { "No se pudo crear el directorio temporal." }
                check(temporary.isDirectory) { "El destino temporal no es una carpeta." }
                ensureNoLinks(temporary)
                copy(source, temporary, sourceManifest)
                check(scan(source) == sourceManifest) { "El origen privado cambió durante la copia." }
                record = record.copy(phase = MigrationPhase.VERIFYING_TEMP).also(::write)
                progress(record.phase)
            }
            verify(source, temporary, sourceManifest, config.branch)
            record = record.copy(phase = MigrationPhase.FINALIZING).also(::write)
            progress(record.phase)
            resolver.requireSharedAccess()
            check(!final.exists()) { "La carpeta final ya existe. No se sobrescribirá." }
            Files.move(temporary.toPath(), final.toPath())
        }

        check(final.isDirectory && !temporary.exists()) {
            "El temporal y la carpeta final presentan un estado ambiguo. La migración queda bloqueada."
        }
        record = record.copy(phase = MigrationPhase.VERIFYING_FINAL).also(::write)
        progress(record.phase)
        verify(source, final, sourceManifest, config.branch)
        resolver.requireSharedAccess()
        if (config.workspaceLocation == WorkspaceLocation.APP_PRIVATE) {
            record = record.copy(phase = MigrationPhase.SWITCHING_REGISTRY).also(::write)
            progress(record.phase)
            check(scan(source) == sourceManifest && scan(final) == sourceManifest) {
                "El origen o el destino cambió antes de activar el nuevo workspace."
            }
            registry.activateShared(repositoryId, record.sourceDirectory, record.finalDirectory)
        }
        val active = registry.load().repositories.first { it.id == repositoryId }
        check(active.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS && resolver.resolve(active) == final) {
            "El registro no activó el workspace verificado."
        }
        return completeActivated(active, record, final, token, onAnalyzed, progress)
    }

    private fun completeActivated(config: RepositoryConfig, record: MigrationRecord, final: File,
        token: CharArray, onAnalyzed: (RepositoryStateSnapshot) -> Unit,
        progress: (MigrationPhase) -> Unit): MigrationRecord {
        check(resolver.resolve(config) == final) { "El workspace activo no coincide con el journal." }
        scan(final)
        val state = RepositoryStateService(repositoryUrl = config.remoteUrl, branch = config.branch, remoteGateway = remoteGateway)
            .refreshState(final, token)
        if (state.authenticationRequired) throw MigrationAuthenticationRequiredException(state.authenticationRejected)
        check(state.type != RepositoryStateType.ERROR) { "El workspace compartido está activo, pero el análisis no terminó: ${state.message}" }
        onAnalyzed(state)
        return record.copy(phase = MigrationPhase.COMPLETED).also { write(it); progress(it.phase) }
    }

    private fun resolveShared(name: String): File {
        resolver.requireSharedAccess()
        val root = resolver.rootFor(WorkspaceLocation.SHARED_DOCUMENTS)
        check(root.isDirectory || root.mkdirs()) { "No se pudo crear Documents/RobGit." }
        require(name.matches(Regex("[a-z0-9.-]+")) && name != "." && name != "..") { "Nombre de destino no válido." }
        check(root.listFiles().orEmpty().none { it.name.equals(name, ignoreCase = true) && it.name != name }) {
            "Ya existe una carpeta de destino con el mismo nombre y otras mayúsculas."
        }
        val child = File(root, name)
        check(!Files.isSymbolicLink(child.toPath())) { "El destino no puede ser un enlace simbólico." }
        return child.canonicalFile.also { require(it.parentFile == root && it.name == name) { "El destino sale de Documents/RobGit." } }
    }

    private data class FileEntry(val directory: Boolean, val size: Long, val sha256: String)

    private fun ensureNoLinks(root: File) {
        Files.walk(root.toPath()).use { paths ->
            paths.forEach { check(!Files.isSymbolicLink(it)) { "El temporal contiene un enlace simbólico." } }
        }
    }

    private fun scan(root: File): Map<String, FileEntry> {
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "El repositorio no es una carpeta segura." }
        val entries = sortedMapOf<String, FileEntry>()
        Files.walk(root.toPath()).use { paths ->
            paths.forEach { path ->
                if (path == root.toPath()) return@forEach
                check(!Files.isSymbolicLink(path)) { "La migración no admite enlaces simbólicos: ${path.fileName}" }
                val relative = root.toPath().relativize(path).toString().replace('\\', '/')
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries[relative] = FileEntry(true, 0, "")
                } else {
                    check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Tipo de archivo no admitido: $relative" }
                    entries[relative] = FileEntry(false, Files.size(path), hashFile(path))
                }
            }
        }
        check(entries.containsKey(".git") && entries[".git"]?.directory == true) { "Falta el directorio Git." }
        return entries
    }

    private fun copy(source: File, destination: File, expected: Map<String, FileEntry>) {
        expected.forEach { (relative, entry) ->
            resolver.requireSharedAccess()
            val target = File(destination, relative)
            val origin = File(source, relative)
            checkNoSymlinksInPath(source, origin)
            checkNoSymlinksInPath(destination, target)
            check(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath())) { "Ruta temporal no permitida." }
            check(!Files.isSymbolicLink(target.toPath())) { "El temporal contiene un enlace simbólico." }
            if (entry.directory) {
                check(target.isDirectory || target.mkdirs()) { "No se pudo copiar el directorio $relative." }
            } else {
                check(requireNotNull(target.parentFile).isDirectory) { "Falta el directorio padre de $relative." }
                if (target.exists()) {
                    check(target.isFile) { "El temporal contiene un directorio donde se esperaba un archivo: $relative" }
                    if (target.length() == entry.size && hashFile(target.toPath()) == entry.sha256) return@forEach
                    check(target.setWritable(true)) { "No se pudo reparar el archivo temporal $relative." }
                }
                Files.copy(origin.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun checkNoSymlinksInPath(root: File, child: File) {
        val base = root.toPath().toAbsolutePath().normalize()
        var current: Path? = child.toPath().toAbsolutePath().normalize()
        check(requireNotNull(current).startsWith(base)) { "La copia intenta salir del repositorio." }
        while (current != null && current != base) {
            check(!Files.isSymbolicLink(current)) { "La copia no admite enlaces simbólicos." }
            current = current.parent
        }
    }

    private fun verify(source: File, destination: File, expected: Map<String, FileEntry>, branch: String) {
        resolver.requireSharedAccess()
        check(scan(source) == expected) { "El origen privado cambió durante la verificación." }
        check(scan(destination) == expected) { "La copia no tiene la misma estructura, tamaño o SHA-256 que el origen." }
        verifyGitEquivalent(source, destination, branch)
        check(scan(source) == expected) { "El origen privado cambió durante la verificación Git." }
        check(scan(destination) == expected) { "La copia cambió durante la verificación Git." }
    }

    private data class GitSnapshot(
        val head: String?, val branch: String, val origin: String?, val trackingRemote: String?,
        val trackingMerge: String?, val remoteHead: String?, val ahead: Int?, val behind: Int?,
        val added: Set<String>, val changed: Set<String>, val modified: Set<String>,
        val removed: Set<String>, val missing: Set<String>, val untracked: Set<String>,
        val conflicting: Set<String>,
    )

    internal fun verifyGitEquivalent(source: File, destination: File, branch: String) {
        check(gitSnapshot(source, branch) == gitSnapshot(destination, branch)) {
            "La copia Git no coincide con el origen: HEAD, rama, remoto, tracking o working tree."
        }
    }

    private fun gitSnapshot(directory: File, configuredBranch: String): GitSnapshot {
        check(File(directory, ".git").isDirectory) { "La copia no contiene un directorio Git." }
        return Git.open(directory).use { git ->
            val repository = git.repository
            check(!repository.isBare && repository.workTree.canonicalFile == directory.canonicalFile) {
                "Git intenta abrir un workspace fuera de la copia."
            }
            val head = repository.resolve("HEAD")
            val remoteHead = repository.resolve("refs/remotes/origin/$configuredBranch")
            val status = git.status().call()
            GitSnapshot(head?.name, repository.branch,
                repository.config.getString("remote", "origin", "url"),
                repository.config.getString("branch", repository.branch, "remote"),
                repository.config.getString("branch", repository.branch, "merge"),
                remoteHead?.name,
                if (head != null && remoteHead != null) git.log().addRange(remoteHead, head).call().count() else null,
                if (head != null && remoteHead != null) git.log().addRange(head, remoteHead).call().count() else null,
                status.added, status.changed, status.modified, status.removed, status.missing,
                status.untracked, status.conflicting)
        }
    }

    private fun fingerprint(entries: Map<String, FileEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (path, entry) ->
            val line = "${path.length}:$path:${entry.directory}:${entry.size}:${entry.sha256}\n"
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().toHex()
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun journalFile(id: String): File {
        require(id.matches(Regex("[a-z0-9-]{1,64}"))) { "Identificador de journal no válido." }
        return File(journalDirectory, "migration-$id.properties")
    }

    private fun validateRecord(record: MigrationRecord, config: RepositoryConfig) {
        check(record.repositoryId == config.id &&
            (record.sourceDirectory == "robgit-pruebas" && config.id == "legacy-robgit-pruebas" ||
                config.id.matches(Regex("[a-f0-9]{32}")) && record.sourceDirectory == "repo-${config.id}") &&
            (12..64).any { record.finalDirectory == sharedDirectoryName(config.displayName, config.id, it) } &&
            record.temporaryDirectory == ".${record.finalDirectory}.migrating-${config.id}" &&
            (record.phase == MigrationPhase.PLANNED && record.sourceFingerprint == null ||
                record.phase != MigrationPhase.PLANNED && record.sourceFingerprint?.matches(Regex("[a-f0-9]{64}")) == true)) {
            "El journal de migración no coincide con el repositorio."
        }
    }

    private fun read(file: File): MigrationRecord {
        check(!Files.isSymbolicLink(file.toPath())) { "El journal no puede ser un enlace simbólico." }
        val values = Properties().apply { file.inputStream().use(::load) }
        check(values.getProperty("sourceLocation") == WorkspaceLocation.APP_PRIVATE.name) { "Origen de journal no válido." }
        val id = requireNotNull(values.getProperty("repositoryId"))
        check(journalFile(id).canonicalFile == file.canonicalFile) { "Journal de migración corrupto." }
        val record = MigrationRecord(id,
            requireNotNull(values.getProperty("sourceDirectory")),
            requireNotNull(values.getProperty("temporaryDirectory")),
            requireNotNull(values.getProperty("finalDirectory")),
            MigrationPhase.valueOf(requireNotNull(values.getProperty("phase"))),
            values.getProperty("sourceFingerprint"))
        check(record.sourceDirectory == "robgit-pruebas" || record.sourceDirectory.matches(Regex("repo-[a-f0-9]{32}"))) {
            "Directorio de backup no válido en el journal."
        }
        check(record.finalDirectory.matches(Regex("[a-z0-9-]+")) &&
            record.temporaryDirectory == ".${record.finalDirectory}.migrating-$id") { "Destinos no válidos en el journal." }
        return record
    }

    private fun write(record: MigrationRecord) {
        check(journalDirectory.isDirectory || journalDirectory.mkdirs()) { "No se pudo crear el journal de migraciones." }
        val file = journalFile(record.repositoryId)
        val temp = File(journalDirectory, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        val values = Properties().apply {
            setProperty("repositoryId", record.repositoryId)
            setProperty("sourceLocation", WorkspaceLocation.APP_PRIVATE.name)
            setProperty("sourceDirectory", record.sourceDirectory)
            setProperty("temporaryDirectory", record.temporaryDirectory)
            setProperty("finalDirectory", record.finalDirectory)
            setProperty("phase", record.phase.name)
            record.sourceFingerprint?.let { setProperty("sourceFingerprint", it) }
        }
        try {
            FileOutputStream(temp).use { stream -> values.store(stream, "RobGit migration; private backup retained"); stream.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}
