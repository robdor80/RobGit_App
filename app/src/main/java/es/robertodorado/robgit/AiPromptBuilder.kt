package es.robertodorado.robgit

internal class AiPromptBuilder {

    fun build(request: AiRequest): String {
        val context = request.context

        val taskInstruction = when (request.task) {
            AiTask.EXPLAIN_STATUS ->
                """
                Explica el estado actual del repositorio en castellano claro y no técnico.
                Usa únicamente los datos proporcionados.
                No inventes operaciones ni estados que no estén presentes.
                Si hay un riesgo o bloqueo, explícalo de forma sencilla.
                """.trimIndent()

            AiTask.SUMMARIZE_CHANGES ->
                """
                Resume de forma clara los cambios detectados en el repositorio.
                No inventes el contenido de los archivos: solo conoces sus rutas y categorías.
                Explica qué tipo de cambios existen y qué debería revisar el usuario.
                """.trimIndent()

            AiTask.SUGGEST_COMMIT_MESSAGE ->
                """
                Propón un único mensaje de commit breve, claro y descriptivo en castellano.
                Debe describir exclusivamente los cambios que pueden inferirse de los metadatos proporcionados.
                No inventes detalles sobre el contenido interno de los archivos.
                Devuelve primero el mensaje recomendado y, debajo, una explicación breve.
                """.trimIndent()

            AiTask.EXPLAIN_BLOCK ->
                """
                Explica por qué RobGit puede estar bloqueando o limitando una operación.
                Usa únicamente el estado y los errores proporcionados.
                No propongas force push, reset --hard ni ninguna operación destructiva.
                La seguridad de RobGit tiene prioridad.
                """.trimIndent()

            AiTask.CUSTOM_QUESTION ->
                """
                Responde a la petición del usuario en castellano claro.
                Usa solamente el contexto proporcionado.
                Si el contexto no permite responder con seguridad, indícalo explícitamente.
                No inventes información sobre archivos cuyo contenido no se ha proporcionado.
                """.trimIndent()
        }

        return buildString {
            appendLine("Eres el asistente integrado de RobGit, una aplicación Android para usar Git de forma segura.")
            appendLine()
            appendLine("REGLAS:")
            appendLine("- No puedes ejecutar ninguna operación Git.")
            appendLine("- No puedes modificar archivos.")
            appendLine("- No puedes realizar push, pull, merge, reset, force push ni borrar nada.")
            appendLine("- Solo analizas y explicas información.")
            appendLine("- No inventes datos ausentes.")
            appendLine("- Responde en castellano de España.")
            appendLine("- Sé claro, práctico y conciso.")
            appendLine("- Responde en texto plano. No uses Markdown, asteriscos, almohadillas ni formato especial.")
            appendLine()
            appendLine("TAREA:")
            appendLine(taskInstruction)
            appendLine()
            appendLine("CONTEXTO DEL REPOSITORIO:")
            appendLine("Nombre: ${context.repositoryName ?: "desconocido"}")
            appendLine("Rama: ${context.branch ?: "desconocida"}")
            appendLine("Estado: ${context.humanStatus.title}")
            appendLine("Explicación actual: ${context.humanStatus.explanation}")

            context.humanStatus.recommendation?.let {
                appendLine("Recomendación de RobGit: $it")
            }

            context.gitStatus?.let { git ->
                appendLine()
                appendLine("ESTADO GIT:")
                appendLine("Tipo: ${git.state}")
                appendLine("Relación local/remoto: ${git.relation}")
                appendLine("Commits locales pendientes: ${git.ahead}")
                appendLine("Commits remotos pendientes: ${git.behind}")
                appendLine("Divergencia: ${git.diverged}")
                appendLine("Conflictos: ${git.hasConflicts}")
                appendLine("Estado remoto actualizado: ${git.remoteStateIsFresh}")
                appendLine("Comprobado: ${git.checkedAt}")
                appendLine("Autenticación requerida: ${git.authenticationRequired}")
                appendLine("Autenticación rechazada: ${git.authenticationRejected}")
                appendLine("Acceso al repositorio denegado: ${git.repositoryAccessDenied}")
            }

            appendFileList("Modificados", context.changes.modified)
            appendFileList("Añadidos", context.changes.added)
            appendFileList("Eliminados", context.changes.deleted)
            appendFileList("Sin seguimiento", context.changes.untracked)
            appendFileList("Staged", context.changes.staged)
            appendFileList("Con conflictos", context.changes.conflicting)

            context.lastOperation?.let { operation ->
                appendLine()
                appendLine("ÚLTIMA OPERACIÓN:")
                appendLine("Operación: ${operation.name}")
                appendLine("Resultado: ${operation.outcome}")
                appendLine("Mensaje: ${operation.message}")
                operation.error?.let { appendLine("Error: $it") }
            }

            context.message?.let {
                appendLine()
                appendLine("Mensaje adicional de RobGit: $it")
            }

            context.error?.let {
                appendLine("Error disponible: $it")
            }

            if (context.omittedFileCount > 0) {
                appendLine()
                appendLine(
                    "RobGit ha omitido ${context.omittedFileCount} rutas por privacidad o límite de contexto."
                )
            }

            request.question?.let {
                appendLine()
                appendLine("PETICIÓN DEL USUARIO:")
                appendLine(it)
            }
        }
    }

    private fun StringBuilder.appendFileList(label: String, paths: List<String>) {
        if (paths.isEmpty()) return

        appendLine()
        appendLine("$label (${paths.size}):")
        paths.forEach { path ->
            appendLine("- $path")
        }
    }
}