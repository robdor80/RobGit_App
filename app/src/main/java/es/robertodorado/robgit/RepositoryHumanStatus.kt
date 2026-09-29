package es.robertodorado.robgit

enum class RepositoryAction { PULL, PUSH, SYNCHRONIZE, AI }

data class RepositoryHumanStatus(
    val title: String,
    val explanation: String,
    val recommendation: String? = null,
    val recommendedAction: RepositoryAction? = null,
    val pullEnabled: Boolean = false,
    val pushEnabled: Boolean = false,
    val blocked: Boolean = false,
)

/** The single translation layer between technical Git state and the main screen. */
object RepositoryStatusPresenter {
    val noRepositories = RepositoryHumanStatus(
        title = "No tienes repositorios configurados.",
        explanation = "Añade un repositorio de GitHub para empezar.",
    )
    val analyzing = RepositoryHumanStatus(
        title = "Comprobando el repositorio…",
        explanation = "RobGit está comprobando este dispositivo y GitHub.",
    )

    val notUpdated = RepositoryHumanStatus(
        title = "Estado sin actualizar",
        explanation = "Pulsa el logo de RobGit para analizar el repositorio.",
    )

    val notPrepared = RepositoryHumanStatus(
        title = "Este repositorio todavía no está preparado en este dispositivo.",
        explanation = "Prepáralo una vez para que RobGit pueda trabajar con él de forma segura.",
    )

    fun present(state: RepositoryStateSnapshot): RepositoryHumanStatus {
        if (state.repositoryAccessDenied) return repositoryAccessDenied()
        if (state.authenticationRejected) return authFailed()
        if (state.authenticationRequired) return authorizationForReading()
        if (state.type == RepositoryStateType.ERROR &&
            (state.error.orEmpty().contains("no existe después del fetch", ignoreCase = true) ||
                state.error.orEmpty().contains("Se esperaba la rama", ignoreCase = true))
        ) {
            return RepositoryHumanStatus(
                "La rama configurada no está disponible.",
                "Comprueba la rama de este repositorio en GitHub.",
                blocked = true,
            )
        }
        if (state.changes.conflictingFiles.isNotEmpty()) {
            return RepositoryHumanStatus(
                "Hay conflictos pendientes.",
                "RobGit ha bloqueado las operaciones automáticas para proteger tus archivos.",
                "Puedes pedir a la IA que te lo explique.",
                RepositoryAction.AI,
                blocked = true,
            )
        }
        if (!state.remoteStateIsFresh || state.type == RepositoryStateType.ERROR) {
            return RepositoryHumanStatus(
                "No he podido comprobar GitHub.",
                "Tus archivos locales no han sido modificados.",
                "Comprueba tu conexión y vuelve a analizar.",
                blocked = true,
            )
        }
        if (state.relation == CommitRelation.DIVERGED) {
            return RepositoryHumanStatus(
                "Las dos copias han evolucionado por separado.",
                "Hay cambios diferentes en este dispositivo y en GitHub.",
                "RobGit no realizará ninguna operación automática para evitar perder trabajo.",
                RepositoryAction.AI,
                blocked = true,
            )
        }
        if (state.changes.hasChanges && state.relation == CommitRelation.REMOTE_AHEAD) {
            return RepositoryHumanStatus(
                "Hay cambios en los dos sitios.",
                "Este dispositivo contiene cambios locales y GitHub también tiene cambios nuevos.",
                "Puedes SINCRONIZAR: RobGit comprobará si afectan a archivos diferentes antes de continuar.",
                RepositoryAction.SYNCHRONIZE,
            )
        }
        if (state.changes.hasChanges) {
            return RepositoryHumanStatus(
                "Tienes trabajo local pendiente.",
                "Hay cambios preparados o cambios locales que todavía no están en GitHub.",
                "Puedes hacer PUSH con seguridad.",
                RepositoryAction.PUSH,
                pushEnabled = state.relation == CommitRelation.SYNCHRONIZED ||
                    state.relation == CommitRelation.LOCAL_AHEAD,
            )
        }
        return when (state.relation) {
            CommitRelation.SYNCHRONIZED -> RepositoryHumanStatus(
                "Todo está al día.",
                "Este dispositivo y GitHub tienen la misma versión. No necesitas hacer nada.",
            )
            CommitRelation.REMOTE_AHEAD -> RepositoryHumanStatus(
                "El repositorio web va por delante del repositorio local.",
                "GitHub tiene cambios que todavía no están en este dispositivo.",
                "Puedes hacer PULL con seguridad.",
                RepositoryAction.PULL,
                pullEnabled = state.ahead == 0 && state.behind > 0,
            )
            CommitRelation.LOCAL_AHEAD -> RepositoryHumanStatus(
                "Tienes cambios pendientes de subir.",
                "Este dispositivo contiene cambios que GitHub todavía no tiene.",
                "Puedes hacer PUSH con seguridad.",
                RepositoryAction.PUSH,
                pushEnabled = state.ahead > 0,
            )
            CommitRelation.DIVERGED -> error("Handled above")
            CommitRelation.UNDETERMINED -> RepositoryHumanStatus(
                "RobGit no ha podido completar la operación.",
                "Tus archivos están protegidos.",
                "Puedes volver a analizar el repositorio.",
                blocked = true,
            )
        }
    }

    fun authRequired() = RepositoryHumanStatus(
        "Para subir cambios necesitas conectar RobGit con GitHub.",
        "Conecta RobGit con GitHub desde Ajustes para continuar.",
        recommendedAction = RepositoryAction.PUSH,
    )

    fun authorizationForReading() = RepositoryHumanStatus(
        "Para comprobar este repositorio necesitas conectar RobGit con GitHub.",
        "Conecta RobGit con GitHub desde Ajustes para continuar.",
        blocked = true,
    )

    fun authFailed() = RepositoryHumanStatus(
        "Es necesario volver a conectar GitHub.",
        "Abre Ajustes y conecta RobGit con GitHub de nuevo.",
        blocked = true,
    )

    fun repositoryAccessDenied() = RepositoryHumanStatus(
        "RobGit no tiene acceso a este repositorio desde GitHub.",
        "Comprueba que la instalación de RobGit RD autoriza este repositorio.",
        blocked = true,
    )

    fun pushUncertain() = RepositoryHumanStatus(
        "No puedo confirmar el resultado de la subida.",
        "RobGit no repetirá el PUSH automáticamente para evitar duplicar operaciones.",
        "Analiza nuevamente el repositorio para comprobar el estado.",
        blocked = true,
    )

    fun genericError() = RepositoryHumanStatus(
        "RobGit no ha podido completar la operación.",
        "Tus archivos están protegidos.",
        "Puedes volver a analizar el repositorio.",
        blocked = true,
    )

    fun present(result: DownloadResult): RepositoryHumanStatus = when (result.outcome) {
        DownloadOutcome.SUCCESS -> RepositoryHumanStatus(
            "PULL completado.",
            "GitHub y este dispositivo vuelven a estar sincronizados.",
        )
        DownloadOutcome.ALREADY_SYNCHRONIZED -> RepositoryHumanStatus(
            "Todo está al día.", "No era necesario descargar nada.",
        )
        DownloadOutcome.BLOCKED_LOCAL_CHANGES -> RepositoryHumanStatus(
            "Tienes trabajo local pendiente.",
            "RobGit no descargará cambios mientras haya trabajo sin guardar en GitHub.",
            "Puedes pedir a la IA que te lo explique.", RepositoryAction.AI, blocked = true,
        )
        DownloadOutcome.BLOCKED_LOCAL_COMMITS -> RepositoryHumanStatus(
            "Tienes cambios pendientes de subir.",
            "No es seguro descargar automáticamente hasta guardar esos cambios en GitHub.",
            "Puedes hacer PUSH con seguridad.", RepositoryAction.PUSH, pushEnabled = true,
        )
        DownloadOutcome.DIVERGED -> RepositoryHumanStatus(
            "Las dos copias han evolucionado por separado.",
            "RobGit no realizará ninguna operación automática para evitar perder trabajo.",
            blocked = true,
        )
        DownloadOutcome.FETCH_ERROR -> if (result.finalState?.repositoryAccessDenied == true)
            repositoryAccessDenied() else if (result.finalState?.authenticationRejected == true)
            authFailed() else if (result.finalState?.authenticationRequired == true)
            authorizationForReading() else fetchError()
        DownloadOutcome.ERROR -> genericError()
    }

    fun present(result: UploadResult): RepositoryHumanStatus {
        if (result.commitCreated && (result.outcome == UploadOutcome.FETCH_ERROR ||
                result.outcome == UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED ||
                result.outcome == UploadOutcome.BLOCKED_REMOTE_AHEAD)) {
            return uploadStoppedAfterCommit(result)
        }
        return when (result.outcome) {
            UploadOutcome.SUCCESS -> RepositoryHumanStatus(
                "PUSH completado.", "Tus cambios ya están guardados en GitHub.",
            )
            UploadOutcome.NOTHING_TO_UPLOAD -> RepositoryHumanStatus(
                "Todo está al día.", "No había cambios pendientes de subir.",
            )
            UploadOutcome.AUTH_REQUIRED -> authRequired()
            UploadOutcome.AUTH_FAILED -> if (result.authenticationRejected) authFailed() else repositoryAccessDenied()
            UploadOutcome.PUSH_UNCERTAIN -> pushUncertain()
            UploadOutcome.BLOCKED_CONFLICTS -> conflicts()
            UploadOutcome.BLOCKED_DIVERGED -> diverged()
            UploadOutcome.FETCH_ERROR -> fetchError()
            UploadOutcome.BLOCKED_REMOTE_AHEAD,
            UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED,
            -> RepositoryHumanStatus(
                "GitHub tiene cambios nuevos.",
                "La subida se ha detenido porque las dos copias ya no coinciden.",
                "RobGit no ha modificado nada para proteger tu trabajo.", blocked = true,
            )
            UploadOutcome.ERROR -> genericError()
        }
    }

    fun present(result: SynchronizationResult): RepositoryHumanStatus {
        val downloaded = result.downloadResult?.outcome == DownloadOutcome.SUCCESS
        val uploadIncomplete = result.uploadResult?.outcome?.let {
            it != UploadOutcome.SUCCESS && it != UploadOutcome.NOTHING_TO_UPLOAD
        } == true
        if (downloaded && (uploadIncomplete || result.outcome == SynchronizationOutcome.ERROR)) {
            return partialSynchronization(result)
        }
        if (result.outcome == SynchronizationOutcome.ERROR && result.downloadResult != null) {
            return RepositoryHumanStatus(
                "No he podido completar la sincronización.",
                result.message,
                "Analiza nuevamente el repositorio para comprobar el estado antes de continuar.",
                blocked = true,
            )
        }
        if (result.uploadResult?.commitCreated == true &&
            (result.outcome == SynchronizationOutcome.FETCH_ERROR ||
                result.outcome == SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED)) {
            return uploadStoppedAfterCommit(result.uploadResult, result.message)
        }
        return when (result.outcome) {
            SynchronizationOutcome.SUCCESS_DOWNLOADED,
            SynchronizationOutcome.SUCCESS_UPLOADED,
            SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED,
            -> synchronizationCompleted(result.finalState)
            SynchronizationOutcome.NOTHING_TO_DO -> RepositoryHumanStatus(
                "Todo está al día.", "No era necesario hacer nada.",
            )
            SynchronizationOutcome.AUTH_REQUIRED -> if (result.finalState?.repositoryAccessDenied == true)
                repositoryAccessDenied() else if (result.finalState?.authenticationRejected == true)
                authFailed() else if (result.finalState?.authenticationRequired == true)
                authorizationForReading() else authRequired()
            SynchronizationOutcome.AUTH_FAILED -> if (result.uploadResult?.authenticationRejected == true)
                authFailed() else repositoryAccessDenied()
            SynchronizationOutcome.PUSH_UNCERTAIN -> pushUncertain()
            SynchronizationOutcome.BLOCKED_CONFLICTS -> conflicts()
            SynchronizationOutcome.BLOCKED_DIVERGED -> diverged()
            SynchronizationOutcome.FETCH_ERROR -> fetchError()
            SynchronizationOutcome.BLOCKED_OVERLAPPING_FILES -> RepositoryHumanStatus(
                "Hay cambios incompatibles en ambos sitios.",
                "Los mismos archivos han cambiado en este dispositivo y en GitHub, o sus rutas pueden colisionar. RobGit no ha realizado ningún cambio.",
                "Puedes resolverlo manualmente o pedir a la IA que te lo explique.",
                RepositoryAction.AI, blocked = true,
            )
            SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES,
            SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED,
            -> RepositoryHumanStatus(
                "Hay cambios en los dos sitios.",
                "Este dispositivo contiene cambios locales y GitHub también tiene cambios nuevos.",
                "No es seguro aplicar una operación automática. RobGit no ha modificado nada.",
                RepositoryAction.AI, blocked = true,
            )
            SynchronizationOutcome.ERROR -> genericError()
        }
    }

    private fun synchronizationCompleted(finalState: RepositoryStateSnapshot?): RepositoryHumanStatus {
        if (finalState == null || !finalState.remoteStateIsFresh || finalState.type == RepositoryStateType.ERROR) {
            return RepositoryHumanStatus(
                "La sincronización terminó; falta comprobar el estado final.",
                "No he podido confirmar que este dispositivo y GitHub estén al día.",
                "Analiza nuevamente el repositorio para comprobar el estado.",
                blocked = true,
            )
        }
        if (finalState.relation != CommitRelation.SYNCHRONIZED || finalState.changes.hasChanges) {
            val current = present(finalState)
            return current.copy(title = "La sincronización terminó; quedan cambios pendientes.")
        }
        return RepositoryHumanStatus(
            "Sincronización completada.",
            "Este dispositivo y GitHub están al día.",
        )
    }

    private fun partialSynchronization(result: SynchronizationResult): RepositoryHumanStatus {
        val uncertain = result.outcome == SynchronizationOutcome.PUSH_UNCERTAIN ||
            result.uploadResult?.outcome == UploadOutcome.PUSH_UNCERTAIN
        val needsAi = result.outcome == SynchronizationOutcome.BLOCKED_DIVERGED ||
            result.outcome == SynchronizationOutcome.BLOCKED_CONFLICTS
        val recommendation = when {
            result.outcome == SynchronizationOutcome.AUTH_REQUIRED ||
                result.outcome == SynchronizationOutcome.AUTH_FAILED -> "Conecta RobGit con GitHub desde Ajustes para continuar."
            needsAi -> "Puedes pedir a la IA que te explique el bloqueo."
            else -> "Analiza nuevamente el repositorio para comprobar el estado antes de continuar."
        }
        return RepositoryHumanStatus(
            if (uncertain) "Descarga completada; subida sin confirmar." else "Descarga completada; subida pendiente.",
            result.message,
            recommendation,
            if (needsAi) RepositoryAction.AI else null,
            blocked = true,
        )
    }

    private fun uploadStoppedAfterCommit(result: UploadResult, message: String = result.message) = RepositoryHumanStatus(
        if (result.outcome == UploadOutcome.FETCH_ERROR) "Cambios guardados localmente; subida pendiente."
            else "GitHub tiene cambios nuevos.",
        message,
        "Analiza nuevamente el repositorio para comprobar el estado antes de continuar.",
        blocked = true,
    )

    private fun fetchError() = RepositoryHumanStatus(
        "No he podido comprobar GitHub.", "Tus archivos locales no han sido modificados.",
        "Comprueba tu conexión y vuelve a analizar.", blocked = true,
    )

    private fun diverged() = RepositoryHumanStatus(
        "Las dos copias han evolucionado por separado.",
        "Hay cambios diferentes en este dispositivo y en GitHub.",
        "RobGit no realizará ninguna operación automática para evitar perder trabajo.",
        RepositoryAction.AI, blocked = true,
    )

    private fun conflicts() = RepositoryHumanStatus(
        "Hay conflictos pendientes.",
        "RobGit ha bloqueado las operaciones automáticas para proteger tus archivos.",
        "Puedes pedir a la IA que te lo explique.", RepositoryAction.AI, blocked = true,
    )
}

/** Small lifecycle policy kept independent from Android so rotation behaviour is testable. */
internal class ForegroundRefreshPolicy {
    private var startedOnce = false
    private var trulyBackgrounded = false

    fun onStart(): Boolean {
        val shouldRefresh = startedOnce && trulyBackgrounded
        startedOnce = true
        trulyBackgrounded = false
        return shouldRefresh
    }

    fun onStop(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) trulyBackgrounded = true
    }
}
