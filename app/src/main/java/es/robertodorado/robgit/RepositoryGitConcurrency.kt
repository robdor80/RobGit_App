package es.robertodorado.robgit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Survives Activity recreation, including a synchronous JGit call still finishing on IO. */
internal object RepositoryGitConcurrency {
    private val mutex = Mutex()
    val isBusy: Boolean get() = mutex.isLocked
    suspend fun <T> run(action: suspend () -> T): T = mutex.withLock { action() }
}
