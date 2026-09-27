package es.robertodorado.robgit

/** Local deterministic demonstration. No network, SDK, file access or Git calls. */
internal class DevelopmentAiProvider : AiProvider {
    override suspend fun complete(request: AiRequest): AiResponse {
        val context = request.context
        val files = context.changes
        val blocked = context.humanStatus.blocked || context.gitStatus?.let {
            it.hasConflicts || it.diverged || it.authenticationRequired || it.authenticationRejected ||
                it.repositoryAccessDenied || !it.remoteStateIsFresh
        } == true
        val body = when (request.task) {
            AiTask.EXPLAIN_STATUS -> listOfNotNull(
                context.humanStatus.title, context.humanStatus.explanation,
                context.gitStatus?.let { "Commits locales pendientes: " + it.ahead + ". Remotos pendientes: " + it.behind + "." },
                context.gitStatus?.takeUnless { it.remoteStateIsFresh }?.let {
                    "El estado remoto disponible no está actualizado."
                },
            ).joinToString("\n")
            AiTask.SUMMARIZE_CHANGES ->
                "Metadatos disponibles: " + files.modified.size + " modificados, " +
                    files.added.size + " añadidos al índice, " + files.deleted.size + " eliminados, " +
                    files.untracked.size + " sin seguimiento y " + files.conflicting.size + " con conflictos.\n" +
                    "No se ha leído el contenido de los archivos."
            AiTask.SUGGEST_COMMIT_MESSAGE -> {
                val count = (files.modified + files.added + files.deleted + files.untracked + files.staged).distinct().size
                if (count == 0) "El contexto no contiene archivos pendientes. No se propone un mensaje de commit."
                else "Borrador de ejemplo: Actualizar " + count + " archivos del repositorio.\n" +
                    "Revísalo antes de usarlo; este asistente no crea ningún commit."
            }
            AiTask.EXPLAIN_BLOCK -> listOfNotNull(
                if (blocked) context.humanStatus.explanation
                else "El contexto disponible no indica un bloqueo.",
                context.error, context.lastOperation?.error,
                "Los bloqueos y las comprobaciones de seguridad siguen siendo responsabilidad de RobGit.",
            ).joinToString("\n")
            AiTask.CUSTOM_QUESTION -> "Pregunta recibida para la demostración: " + request.question.orEmpty() +
                "\nEste proveedor simulado no puede responder preguntas libres como una IA real."
        }
        val omitted = if (context.omittedFileCount > 0)
            "\nSe han omitido " + context.omittedFileCount + " rutas por privacidad o por el límite de contexto." else ""
        return AiResponse("Demostración de desarrollo: no hay una IA real conectada.\n\n" + body + omitted,
            AiResponseSource.DEVELOPMENT)
    }
}
