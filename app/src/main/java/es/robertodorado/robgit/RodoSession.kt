package es.robertodorado.robgit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** UI-facing, informational state only. No repository handle or Git operation enters this class. */
internal sealed interface RodoState {
    data object Idle : RodoState
    data class Thinking(val requestId: Long) : RodoState
    data class Completed(val requestId: Long, val text: String) : RodoState
    data class Warning(val requestId: Long, val message: String) : RodoState
    data class Error(val requestId: Long, val message: String) : RodoState
}

internal class RodoSession(
    private val scope: CoroutineScope,
    private val service: AiAssistantService,
) {
    private val mutableState = MutableStateFlow<RodoState>(RodoState.Idle)
    val state: StateFlow<RodoState> = mutableState
    private var requestId = 0L
    private var requestJob: Job? = null
    private var selectedRepositoryId: String? = null
    private var selectionInitialized = false

    /** Activity recreation keeps the answer; a genuinely different repository invalidates it. */
    fun selectRepository(id: String?) {
        if (selectionInitialized && selectedRepositoryId == id) return
        selectedRepositoryId = id
        selectionInitialized = true
        reset()
    }

    /** Sets Thinking synchronously so the selector can close before the provider suspends. */
    fun submit(task: AiTask, context: AiContext, question: String): Boolean {
        if (mutableState.value is RodoState.Thinking) return false
        val id = ++requestId
        mutableState.value = RodoState.Thinking(id)
        requestJob = scope.launch {
            try {
                val result = service.execute(task, context, question)
                if (requestId == id) mutableState.value = when (result) {
                    is AiAssistantResult.Completed -> RodoState.Completed(id, result.response.text)
                    is AiAssistantResult.Unavailable -> RodoState.Warning(id, result.message)
                    is AiAssistantResult.Failed -> RodoState.Error(id, result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unexpected provider/dispatcher details must never reach the screen.
                if (requestId == id) mutableState.value =
                    RodoState.Error(id, "Rodo no ha podido responder. Puedes intentarlo de nuevo.")
            }
        }
        return true
    }

    /** Repository changes invalidate both the visible answer and any pending completion. */
    fun reset() {
        requestId++
        requestJob?.cancel()
        requestJob = null
        mutableState.value = RodoState.Idle
    }
}

/** ViewModel scope retains a pending informational request across tablet rotation. */
internal class RodoViewModel : ViewModel() {
    val session = RodoSession(viewModelScope, AiAssistantService(FirebaseGeminiAiProvider()))
}
