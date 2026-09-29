package es.robertodorado.robgit

import java.io.Closeable
import java.io.File
import java.nio.file.Files

internal enum class RepositoryFileEventKind { WRITE, CREATE, DELETE, MOVE_FROM, MOVE_TO, ATTRIBUTES, DELETE_SELF, MOVE_SELF }
internal data class RepositoryFileEvent(val kind: RepositoryFileEventKind, val name: String?)
internal fun interface DirectoryObserverFactory {
    fun open(directory: File, onEvent: (RepositoryFileEvent) -> Unit): Closeable
}

/** Directory metadata only: never opens user content or follows symlinks.
 * Initial registration visits directories once; topology events visit only the affected subtree.
 * .git objects/logs/remotes are excluded. Index/HEAD/local refs detect external staging/commits.
 */
internal class RecursiveRepositoryWatcher(
    private val root: File,
    private val observers: DirectoryObserverFactory,
    private val onChange: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private val watches = mutableMapOf<File, Closeable>()
    private var closed = false

    @Synchronized
    fun start(): RecursiveRepositoryWatcher {
        try {
            check(root.isDirectory && root.canRead() && !Files.isSymbolicLink(root.toPath())) {
                "No se puede observar el workspace."
            }
            addTree(root)
            check(File(root, ".git").isDirectory) { "El repositorio ya no está preparado." }
            return this
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun relative(file: File): String = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    private fun watchedDirectory(file: File): Boolean {
        val path = relative(file)
        return if (path == ".git" || path == ".git/refs" || path == ".git/refs/heads" || path.startsWith(".git/refs/heads/")) true
        else path.split('/').none { it == ".git" }
    }

    private fun relevant(path: String): Boolean {
        if (path == ".git") return true
        if (!path.startsWith(".git/")) return path.split('/').none { it == ".git" }
        if (path.endsWith(".lock")) return false
        return path in setOf(".git/index", ".git/HEAD", ".git/config", ".git/packed-refs",
            ".git/MERGE_HEAD", ".git/CHERRY_PICK_HEAD", ".git/REVERT_HEAD", ".git/rebase-merge", ".git/rebase-apply") ||
            path == ".git/refs/heads" || path.startsWith(".git/refs/heads/")
    }

    private fun addTree(directory: File) {
        if (closed || watches.containsKey(directory) || !watchedDirectory(directory) ||
            Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return
        check(directory.canRead()) { "No se puede leer un directorio del workspace." }
        // Register the parent first: creates during traversal cannot be lost.
        watches[directory] = observers.open(directory) { event -> handle(directory, event) }
        val children = directory.listFiles() ?: throw WorkspaceAccessException("No se puede enumerar el workspace.")
        children.forEach { child ->
            if (watchedDirectory(child) && !Files.isSymbolicLink(child.toPath()) && child.isDirectory) addTree(child)
        }
    }

    private fun removeTree(directory: File) {
        // Ordinary file deletion/rename is O(1); only an observed directory needs subtree cleanup.
        if (directory !in watches) return
        watches.keys.filter { it == directory || it.toPath().startsWith(directory.toPath()) }.forEach {
            watches.remove(it)?.close()
        }
    }

    @Synchronized
    private fun handle(directory: File, event: RepositoryFileEvent) {
        if (closed || directory !in watches) return
        try {
            if (event.kind == RepositoryFileEventKind.DELETE_SELF || event.kind == RepositoryFileEventKind.MOVE_SELF) {
                if (directory == root || directory == File(root, ".git")) {
                    throw WorkspaceAccessException("El workspace ha sido movido o eliminado.")
                }
                removeTree(directory)
                onChange()
                return
            }
            val name = event.name ?: run {
                check(directory.canRead()) { "RobGit ha perdido acceso al workspace." }
                if (event.kind == RepositoryFileEventKind.ATTRIBUTES) onChange()
                return
            }
            // FileObserver names are a direct child, never caller-supplied traversal paths.
            if (name == "." || name == ".." || '/' in name || '\\' in name) return
            val child = File(directory, name)
            val path = relative(child)
            if (event.kind == RepositoryFileEventKind.MOVE_FROM || event.kind == RepositoryFileEventKind.DELETE) {
                removeTree(child)
                if (path == ".git") throw WorkspaceAccessException("El repositorio ya no está preparado.")
            }
            if (event.kind == RepositoryFileEventKind.CREATE || event.kind == RepositoryFileEventKind.MOVE_TO) {
                addTree(child)
            }
            if (relevant(path)) onChange()
            if (!root.canRead()) throw WorkspaceAccessException("RobGit ha perdido acceso al workspace.")
        } catch (failure: Exception) {
            close()
            onFailure(failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        watches.values.forEach { it.close() }
        watches.clear()
    }
}
