package es.robertodorado.robgit

import kotlinx.coroutines.CancellationException

/** Orchestrates text requests only. No Git engine, repository handle, auth store or I/O. */
internal class AiAssistantService(
    private val provider: AiProvider,
    private val filter: AiContextFilter = AiContextFilter(),
) {
    suspend fun execute(task: AiTask, context: AiContext, question: String = ""): AiAssistantResult {
        if (task.requiresAnalyzedState && context.gitStatus == null) {
            return AiAssistantResult.Unavailable(ANALYSIS_REQUIRED_MESSAGE)
        }
        val safeQuestion = filter.sanitizeText(question, AiContextFilter.MAX_QUESTION)
        if (task == AiTask.CUSTOM_QUESTION && safeQuestion == null) {
            return AiAssistantResult.Unavailable("Escribe una pregunta para el asistente.")
        }
        val request = AiRequest(task, filter.filter(context), safeQuestion)
        return try {
            AiAssistantResult.Completed(provider.complete(request))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Provider exception messages may contain private input or credentials.
            AiAssistantResult.Failed("No se pudo obtener una respuesta del asistente. Puedes intentarlo de nuevo.")
        }
    }

    companion object {
        const val ANALYSIS_REQUIRED_MESSAGE = "Primero analiza el repositorio pulsando el logo de RobGit."
    }
}
