package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.nio.file.Files

class RecursiveRepositoryWatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun existingSubdirectoriesAndNewSubtreesAreObserved() {
        val h = fixture()
        val existing = File(h.root, "assets/deep").apply { mkdirs() }
        h.start()
        assertTrue(h.backend.active(existing))
        val new = File(h.root, "new/deep").apply { mkdirs() }
        h.backend.emit(h.root, RepositoryFileEventKind.CREATE, "new")
        assertTrue(h.backend.active(new))
        h.backend.emit(new, RepositoryFileEventKind.WRITE, "image.png")
        assertEquals(2, h.events)
        h.watcher.close()
        assertTrue(h.backend.watches.all { it.closed })
    }

    @Test fun movedDirectoriesCloseOldSubtreeAndRegisterNewPaths() {
        val h = fixture()
        val old = File(h.root, "old/deep").apply { mkdirs() }
        h.start()
        assertTrue(File(h.root, "old").renameTo(File(h.root, "new")))
        h.backend.emit(h.root, RepositoryFileEventKind.MOVE_FROM, "old")
        h.backend.emit(h.root, RepositoryFileEventKind.MOVE_TO, "new")
        assertFalse(h.backend.active(old))
        assertTrue(h.backend.active(File(h.root, "new/deep")))
        h.watcher.close()
    }

    @Test fun deletedDirectoriesCloseTheirObservers() {
        val h = fixture()
        val folder = File(h.root, "folder/deep").apply { mkdirs() }
        h.start()
        assertTrue(File(h.root, "folder").deleteRecursively())
        h.backend.emit(h.root, RepositoryFileEventKind.DELETE, "folder")
        assertFalse(h.backend.active(folder))
        assertEquals(1, h.events)
        h.watcher.close()
    }

    @Test fun gitObjectsLogsRemoteRefsAndLockFilesAreIgnoredButIndexAndLocalRefsAreDetected() {
        val h = fixture()
        val git = File(h.root, ".git")
        listOf("objects/pack", "logs", "refs/remotes/origin", "refs/heads").forEach { File(git, it).mkdirs() }
        h.start()
        assertFalse(h.backend.active(File(git, "objects")))
        assertFalse(h.backend.active(File(git, "logs")))
        assertFalse(h.backend.active(File(git, "refs/remotes")))
        listOf("FETCH_HEAD", "ORIG_HEAD", "index.lock", "packed-refs.lock", "objects", "logs").forEach {
            h.backend.emit(git, RepositoryFileEventKind.WRITE, it)
        }
        assertEquals(0, h.events)
        h.backend.emit(File(git, "refs/heads"), RepositoryFileEventKind.WRITE, "main.lock")
        assertEquals(0, h.events)
        listOf("index", "HEAD", "MERGE_HEAD", "config").forEach {
            h.backend.emit(git, RepositoryFileEventKind.MOVE_TO, it)
        }
        h.backend.emit(File(git, "refs/heads"), RepositoryFileEventKind.MOVE_TO, "main")
        assertEquals(5, h.events)
        h.watcher.close()
    }

    @Test fun workspaceDeletionDegradesSafelyAndClosesEveryObserver() {
        val h = fixture(); h.start()
        h.backend.emit(h.root, RepositoryFileEventKind.DELETE_SELF, null)
        assertEquals(1, h.failures.size)
        assertTrue(h.backend.watches.all { it.closed })
    }

    @Test fun removingGitDirectoryStopsAnUnpreparedRepository() {
        val h = fixture(); h.start()
        h.backend.emit(h.root, RepositoryFileEventKind.DELETE, ".git")
        assertEquals(1, h.failures.size)
        assertTrue(h.backend.watches.all { it.closed })
    }

    @Test fun unreadableOrMissingWorkspaceFailsWithoutLeavingObservers() {
        val h = fixture()
        assertTrue(h.root.deleteRecursively())
        try { h.start(); fail("Expected unavailable workspace") } catch (_: IllegalStateException) { }
        assertTrue(h.backend.watches.none { !it.closed })
    }

    @Test fun watcherNeverChangesFileContentsOrMetadataFiles() {
        val h = fixture()
        File(h.root, "tracked.txt").writeText("original")
        File(h.root, ".git/index").writeText("test index")
        File(h.root, ".git/HEAD").writeText("ref: refs/heads/main")
        val before = image(h.root)
        h.start()
        h.backend.emit(h.root, RepositoryFileEventKind.WRITE, "tracked.txt")
        h.watcher.close()
        assertEquals(before, image(h.root))
    }

    @Test fun registrationFailureClosesPreviouslyRegisteredDirectories() {
        val h = fixture()
        h.backend.failOn = ".git"
        try { h.start(); fail("Expected observer error") } catch (_: SecurityException) { }
        assertTrue(h.backend.watches.all { it.closed })
    }

    @Test fun nestedGitDirectoryAndTraversalEventAreIgnored() {
        val h = fixture()
        File(h.root, "nested/.git/objects").mkdirs()
        h.start()
        assertFalse(h.backend.active(File(h.root, "nested/.git")))
        h.backend.emit(h.root, RepositoryFileEventKind.WRITE, "../outside")
        assertEquals(0, h.events)
        h.watcher.close()
    }

    private fun image(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.toPath().relativize(it).toString() to Files.readAllBytes(it).joinToString()
        }
    }

    private fun fixture(): Harness = Harness(temporary.newFolder().apply { File(this, ".git").mkdir() })

    private class Harness(val root: File) {
        val backend = Backend()
        var events = 0
        val failures = mutableListOf<Exception>()
        val watcher = RecursiveRepositoryWatcher(root, backend, { events++ }, { failures += it })
        fun start() { watcher.start() }
    }

    private class Watch(val directory: File, val callback: (RepositoryFileEvent) -> Unit) : Closeable {
        var closed = false
        override fun close() { closed = true }
    }
    private class Backend : DirectoryObserverFactory {
        val watches = mutableListOf<Watch>()
        var failOn: String? = null
        override fun open(directory: File, onEvent: (RepositoryFileEvent) -> Unit): Closeable {
            if (directory.name == failOn) throw SecurityException("denied")
            return Watch(directory, onEvent).also { watches += it }
        }
        fun active(directory: File) = watches.any { it.directory == directory && !it.closed }
        fun emit(directory: File, kind: RepositoryFileEventKind, name: String?) {
            watches.last { it.directory == directory && !it.closed }.callback(RepositoryFileEvent(kind, name))
        }
    }
}
