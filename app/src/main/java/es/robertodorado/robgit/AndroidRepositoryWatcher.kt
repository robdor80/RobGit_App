package es.robertodorado.robgit

import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

internal class AndroidRepositoryWatcherFactory : RepositoryWatcherFactory {
    override suspend fun open(directory: File, onChange: () -> Unit, onFailure: (Exception) -> Unit): Closeable {
        var opened: RecursiveRepositoryWatcher? = null
        try {
            return withContext(Dispatchers.IO) {
                RecursiveRepositoryWatcher(directory, AndroidDirectoryObserverFactory, onChange, onFailure).also {
                    opened = it
                    it.start()
                }
            }
        } catch (failure: Exception) {
            // Includes cancellation at the IO -> Main return dispatch.
            opened?.close()
            throw failure
        }
    }
}

internal object AndroidDirectoryObserverFactory : DirectoryObserverFactory {
    override fun open(directory: File, onEvent: (RepositoryFileEvent) -> Unit): Closeable {
        val mask = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(directory, mask) {
            override fun onEvent(event: Int, path: String?) {
                val kind = when {
                    event and DELETE_SELF != 0 -> RepositoryFileEventKind.DELETE_SELF
                    event and MOVE_SELF != 0 -> RepositoryFileEventKind.MOVE_SELF
                    event and MOVED_FROM != 0 -> RepositoryFileEventKind.MOVE_FROM
                    event and MOVED_TO != 0 -> RepositoryFileEventKind.MOVE_TO
                    event and CREATE != 0 -> RepositoryFileEventKind.CREATE
                    event and DELETE != 0 -> RepositoryFileEventKind.DELETE
                    event and ATTRIB != 0 -> RepositoryFileEventKind.ATTRIBUTES
                    event and (MODIFY or CLOSE_WRITE) != 0 -> RepositoryFileEventKind.WRITE
                    else -> return
                }
                onEvent(RepositoryFileEvent(kind, path))
            }
        }
        observer.startWatching()
        // Captures a strong reference until closed: FileObserver stops if collected.
        return Closeable { observer.stopWatching() }
    }
}
