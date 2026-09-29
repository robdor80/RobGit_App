package es.robertodorado.robgit

data class SharedWorkspaceFeedback(val title: String, val detail: String)

internal fun sharedWorkspaceFeedback(result: SharedWorkspaceResult): SharedWorkspaceFeedback {
    if (result.authenticationRequired) {
        return SharedWorkspaceFeedback("Hace falta autorización de GitHub", result.message)
    }
    if (!result.success) {
        return SharedWorkspaceFeedback("La operación no se completó", result.message)
    }

    return when (result.operation) {
        "PREPARAR WORKSPACE" -> SharedWorkspaceFeedback("Workspace preparado correctamente", result.message)
        "ANALIZAR WORKSPACE" -> result.state?.let { state ->
            val tree = if (state.changes.hasChanges) "con cambios" else "limpio"
            val details = buildList {
                add("Rama: ${state.branch ?: SHARED_WORKSPACE_BRANCH}")
                add("Ahead: ${state.ahead}")
                add("Behind: ${state.behind}")
                add("Working tree: $tree")
                summarizeFiles(state.changes.newFiles, "archivo nuevo", "archivos nuevos")?.let(::add)
                summarizeFiles(state.changes.modifiedFiles, "archivo modificado", "archivos modificados")?.let(::add)
                summarizeFiles(state.changes.deletedFiles, "archivo eliminado", "archivos eliminados")?.let(::add)
                state.changes.stagedFiles.takeIf { it.isNotEmpty() }?.let {
                    add("Archivos preparados: ${it.joinToString()}")
                }
                state.changes.conflictingFiles.takeIf { it.isNotEmpty() }?.let {
                    add("Conflictos: ${it.joinToString()}")
                }
            }.joinToString("\n")
            SharedWorkspaceFeedback("Repositorio analizado correctamente", details)
        } ?: SharedWorkspaceFeedback("Análisis completado", result.message)
        "PRUEBA FILESYSTEM" -> SharedWorkspaceFeedback("Prueba de filesystem completada correctamente", result.message)
        "PULL DE PRUEBA" -> when (result.outcome) {
            DownloadOutcome.SUCCESS.name -> SharedWorkspaceFeedback("PULL completado correctamente", result.message)
            DownloadOutcome.ALREADY_SYNCHRONIZED.name -> SharedWorkspaceFeedback("El workspace ya estaba sincronizado", result.message)
            else -> SharedWorkspaceFeedback("PULL completado", result.message)
        }
        "PUSH DE PRUEBA" -> when (result.outcome) {
            UploadOutcome.SUCCESS.name -> SharedWorkspaceFeedback("PUSH completado correctamente", result.message)
            UploadOutcome.NOTHING_TO_UPLOAD.name -> SharedWorkspaceFeedback("No había cambios que subir", result.message)
            else -> SharedWorkspaceFeedback("PUSH completado", result.message)
        }
        "SINCRONIZAR WORKSPACE" -> when (result.outcome) {
            SynchronizationOutcome.NOTHING_TO_DO.name -> SharedWorkspaceFeedback("El workspace ya estaba sincronizado", result.message)
            SynchronizationOutcome.SUCCESS_DOWNLOADED.name -> SharedWorkspaceFeedback("Sincronización completada: cambios descargados", result.message)
            SynchronizationOutcome.SUCCESS_UPLOADED.name -> SharedWorkspaceFeedback("Sincronización completada: cambios subidos", result.message)
            SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED.name -> SharedWorkspaceFeedback("Sincronización completada: cambios descargados y subidos", result.message)
            else -> SharedWorkspaceFeedback("Sincronización completada", result.message)
        }
        else -> SharedWorkspaceFeedback("Operación completada", result.message)
    }
}

private fun summarizeFiles(files: Set<String>, singular: String, plural: String): String? {
    if (files.isEmpty()) return null
    val count = files.size
    val noun = if (count == 1) singular else plural
    val verb = if (count == 1) "Se detectó" else "Se detectaron"
    return "$verb $count $noun: ${files.joinToString()}"
}
