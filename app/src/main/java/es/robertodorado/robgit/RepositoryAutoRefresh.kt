package es.robertodorado.robgit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal data class RepositoryWatchTarget(val repositoryId: String, val directory: File)

internal fun interface RepositoryWatcherFactory {
    suspend fun open(directory: File, onChange: () -> Unit, onFailure: (Exception) -> Unit): Closeable
}

/** All control methods run on the UI thread; watcher callbacks only write to a bounded channel.
 * One consumer debounces and reads sequentially, so a second event never cancels a JGit read.
 */
internal class RepositoryAutoRefresh(
    private val scope: CoroutineScope,
    private val factory: RepositoryWatcherFactory,
    private val refreshLocal: suspend (RepositoryWatchTarget) -> RepositoryStateSnapshot,
    private val publish: (RepositoryStateSnapshot) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val debounceMillis: Long = 750,
) : Closeable {
    private var target: RepositoryWatchTarget? = null
    private var busy = false
    private var closed = false
    private var generation = 0L
    private var worker: Job? = null
    private var watcher: Closeable? = null

    fun select(next: RepositoryWatchTarget?) {
        if (closed || target == next) return
        stop()
        target = next
        start()
    }

    /** Stop before the operation begins, discarding old timers and queued FileObserver callbacks.
     * Reopening after its finalState creates a new callback generation, with no redundant refresh.
     */
    fun operationActive(active: Boolean) {
        if (closed || busy == active) return
        busy = active
        stop()
        start()
    }

    fun shouldAnalyzeOnForeground(eligible: Boolean, gitBusy: Boolean): Boolean =
        !closed && eligible && !busy && !gitBusy

    private fun stop() {
        generation++
        watcher?.close()
        watcher = null
        worker?.cancel()
        worker = null
    }

    private fun start() {
        val selected = target ?: return
        if (busy || closed) return
        val version = generation
        worker = scope.launch {
            val events = Channel<Unit>(Channel.CONFLATED)
            val watcherError = AtomicReference<Exception?>(null)
            var opened: Closeable? = null
            try {
                opened = factory.open(selected.directory,
                    { events.trySend(Unit) }, { watcherError.compareAndSet(null, it); events.trySend(Unit) })
                currentCoroutineContext().ensureActive()
                if (version != generation) return@launch
                watcher = opened
                while (true) {
                    events.receive()
                    watcherError.get()?.let { throw it }
                    // Each event resets the timeout. No coroutine is created per event.
                    while (true) {
                        withTimeoutOrNull(debounceMillis) { events.receive() } ?: break
                        watcherError.get()?.let { throw it }
                    }
                    watcherError.get()?.let { throw it }
                    val result = refreshLocal(selected)
                    currentCoroutineContext().ensureActive()
                    if (version == generation && !busy) publish(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version == generation && !closed) onFailure(failure)
            } finally {
                events.close()
                opened?.close()
                if (version == generation) watcher = null
            }
        }
    }

    override fun close() {
        closed = true
        stop()
        target = null
    }
}
