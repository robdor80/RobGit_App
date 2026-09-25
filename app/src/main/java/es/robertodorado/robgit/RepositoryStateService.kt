package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.time.Instant

private const val FUNCTIONAL_REPOSITORY_URL = "https://github.com/robdor80/Robgit.pruebas.git"

enum class RepositoryStateType {
    SYNCHRONIZED,
    LOCAL_CHANGES,
    LOCAL_AHEAD,
    REMOTE_AHEAD,
    DIVERGED,
    ERROR,
}

enum class CommitRelation {
    SYNCHRONIZED,
    LOCAL_AHEAD,
    REMOTE_AHEAD,
    DIVERGED,
    UNDETERMINED,
}

data class WorkingTreeChanges(
    val newFiles: Set<String> = emptySet(),
    val modifiedFiles: Set<String> = emptySet(),
    val deletedFiles: Set<String> = emptySet(),
    val stagedFiles: Set<String> = emptySet(),
    val conflictingFiles: Set<String> = emptySet(),
) {
    val hasChanges: Boolean
        get() = newFiles.isNotEmpty() || modifiedFiles.isNotEmpty() ||
            deletedFiles.isNotEmpty() || stagedFiles.isNotEmpty() ||
            conflictingFiles.isNotEmpty()
}

data class RepositoryStateSnapshot(
    val type: RepositoryStateType,
    val relation: CommitRelation,
    val message: String,
    val branch: String?,
    val localHead: String?,
    val remoteHead: String?,
    val ahead: Int,
    val behind: Int,
    val changes: WorkingTreeChanges,
    val checkedAt: Instant,
    val remoteStateIsFresh: Boolean,
    val error: String? = null,
)

data class RepositoryPreparationResult(
    val success: Boolean,
    val repositoryPath: String,
    val cloned: Boolean,
    val message: String,
    val error: String? = null,
)

enum class DownloadOutcome {
    SUCCESS,
    ALREADY_SYNCHRONIZED,
    BLOCKED_LOCAL_CHANGES,
    BLOCKED_LOCAL_COMMITS,
    DIVERGED,
    FETCH_ERROR,
    ERROR,
}

data class DownloadResult(
    val outcome: DownloadOutcome,
    val message: String,
    val previousHead: String?,
    val newHead: String?,
    val commitsDownloaded: Int,
    val workingTreeClean: Boolean,
    val finalState: RepositoryStateSnapshot?,
    val error: String? = null,
)

/** Prepares and reads the single persistent functional repository used at this stage. */
class RepositoryStateService(
    private val repositoryUrl: String = FUNCTIONAL_REPOSITORY_URL,
) {
    fun isPrepared(repositoryDirectory: File): Boolean = try {
        File(repositoryDirectory, ".git").isDirectory &&
            Git.open(repositoryDirectory).use { git ->
                !git.repository.isBare && git.repository.objectDatabase.exists()
            }
    } catch (_: Exception) {
        false
    }

    fun prepare(repositoryDirectory: File): RepositoryPreparationResult {
        try {
            if (repositoryDirectory.exists()) {
                check(File(repositoryDirectory, ".git").isDirectory) {
                    "La carpeta persistente existe, pero no contiene un repositorio Git. No se modificará."
                }
                Git.open(repositoryDirectory).use { git ->
                    check(!git.repository.isBare && git.repository.objectDatabase.exists()) {
                        "El repositorio persistente no es válido. No se modificará."
                    }
                    val origin = git.repository.config.getString("remote", "origin", "url")
                    check(origin != null && URIish(origin) == URIish(repositoryUrl)) {
                        "El repositorio existente utiliza otro remoto. No se modificará."
                    }
                }
                return RepositoryPreparationResult(
                    success = true,
                    repositoryPath = repositoryDirectory.absolutePath,
                    cloned = false,
                    message = "Repositorio persistente abierto.",
                )
            }

            Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(repositoryDirectory)
                .setCredentialsProvider(null)
                .setTimeout(60)
                .call()
                .use { git ->
                    check(git.repository.branch == "main") {
                        "Se esperaba la rama main después del clone."
                    }
                }
            return RepositoryPreparationResult(
                success = true,
                repositoryPath = repositoryDirectory.absolutePath,
                cloned = true,
                message = "Repositorio persistente clonado.",
            )
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            return RepositoryPreparationResult(
                success = false,
                repositoryPath = repositoryDirectory.absolutePath,
                cloned = false,
                message = "No se pudo preparar el repositorio.",
                error = causeChain(failure),
            )
        }
    }

    /** Fetches origin and then calculates state without changing HEAD or working-tree files. */
    fun refreshState(repositoryDirectory: File): RepositoryStateSnapshot {
        val checkedAt = Instant.now()
        try {
            check(File(repositoryDirectory, ".git").isDirectory) {
                "El repositorio persistente todavía no está preparado."
            }
            Git.open(repositoryDirectory).use { git ->
                validateFunctionalRepository(git)

                try {
                    fetchOrigin(git)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    return errorSnapshot(
                        checkedAt,
                        "No se pudo actualizar el estado desde GitHub. El estado remoto no está verificado.",
                        failure,
                    )
                }
                return inspectFetchedState(git, checkedAt)
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            return errorSnapshot(
                checkedAt,
                "No se ha podido determinar el estado del repositorio con seguridad.",
                failure,
            )
        }
    }

    /** Revalidates with fetch and applies only a verified fast-forward of main. */
    fun downloadFastForward(repositoryDirectory: File): DownloadResult {
        val checkedAt = Instant.now()
        try {
            check(File(repositoryDirectory, ".git").isDirectory) {
                "El repositorio persistente todavía no está preparado."
            }
            Git.open(repositoryDirectory).use { git ->
                validateFunctionalRepository(git)
                try {
                    fetchOrigin(git)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    val failureState = errorSnapshot(
                        checkedAt,
                        "No se pudo actualizar el estado desde GitHub. El estado remoto no está verificado.",
                        failure,
                    )
                    return downloadError(
                        "No se ha podido comprobar GitHub con seguridad. No se realizó ningún cambio.",
                        failure,
                        finalState = failureState,
                        outcome = DownloadOutcome.FETCH_ERROR,
                    )
                }

                val state = inspectFetchedState(git, checkedAt)
                val blocked = blockedDownloadResult(state)
                if (blocked != null) return blocked

                val repository = git.repository
                val previousHead = repository.resolve(Constants.HEAD)
                val fetchedRemoteHead = repository.resolve("refs/remotes/origin/main")
                check(previousHead != null && fetchedRemoteHead != null)

                // Guard again immediately before MergeCommand in case local files or refs changed.
                val immediateChanges = readWorkingTreeChanges(git)
                val immediateLocalHead = repository.resolve(Constants.HEAD)
                val immediateRemoteHead = repository.resolve("refs/remotes/origin/main")
                if (immediateChanges.hasChanges ||
                    immediateLocalHead != previousHead ||
                    immediateRemoteHead != fetchedRemoteHead
                ) {
                    val latest = inspectFetchedState(git, Instant.now())
                    return blockedDownloadResult(latest) ?: downloadError(
                        "El repositorio cambió durante la comprobación. No se realizó ningún cambio.",
                    )
                }

                val mergeResult = git.merge()
                    .include(fetchedRemoteHead)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .setCommit(false)
                    .call()

                if (mergeResult.mergeStatus != MergeResult.MergeStatus.FAST_FORWARD) {
                    val after = inspectFetchedState(git, Instant.now())
                    if (after.type == RepositoryStateType.SYNCHRONIZED) {
                        return DownloadResult(
                            outcome = DownloadOutcome.ALREADY_SYNCHRONIZED,
                            message = "El repositorio ya está sincronizado.",
                            previousHead = previousHead.name,
                            newHead = after.localHead,
                            commitsDownloaded = 0,
                            workingTreeClean = !after.changes.hasChanges,
                            finalState = after,
                        )
                    }
                    return downloadError(
                        "JGit no realizó un fast-forward seguro (${mergeResult.mergeStatus}).",
                        finalState = after,
                    )
                }

                val finalState = inspectFetchedState(git, Instant.now())
                val newHead = repository.resolve(Constants.HEAD)
                val finalRemoteHead = repository.resolve("refs/remotes/origin/main")
                check(newHead == fetchedRemoteHead) {
                    "HEAD no coincide con el commit remoto obtenido mediante fetch."
                }
                check(finalRemoteHead == fetchedRemoteHead) {
                    "origin/main cambió durante la operación."
                }
                check(finalState.type == RepositoryStateType.SYNCHRONIZED)
                check(finalState.ahead == 0 && finalState.behind == 0)
                check(!finalState.changes.hasChanges) { "El working tree final no está limpio." }

                // HEAD is exactly the pre-existing fetched commit, so no merge commit was created.
                return DownloadResult(
                    outcome = DownloadOutcome.SUCCESS,
                    message = "GitHub tenía ${state.behind} commit(s) nuevos. Los cambios se han descargado correctamente.",
                    previousHead = previousHead.name,
                    newHead = newHead.name,
                    commitsDownloaded = state.behind,
                    workingTreeClean = true,
                    finalState = finalState,
                )
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            return downloadError(
                "No se ha podido completar DESCARGAR con seguridad.",
                failure,
                finalState = errorSnapshot(
                    checkedAt,
                    "No se ha podido determinar el estado del repositorio con seguridad.",
                    failure,
                ),
            )
        }
    }

    private fun validateFunctionalRepository(git: Git) {
        val repository = git.repository
        check(!repository.isBare && repository.objectDatabase.exists()) {
            "El repositorio persistente no es válido."
        }
        check(repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
            "El repositorio tiene una operación Git incompleta (${repository.repositoryState})."
        }
        check(repository.branch == "main") {
            "Se esperaba la rama main, se obtuvo ${repository.branch}."
        }
        val origin = repository.config.getString("remote", "origin", "url")
        check(origin != null && URIish(origin) == URIish(repositoryUrl)) {
            "El repositorio utiliza un remoto distinto del esperado."
        }
    }

    private fun fetchOrigin(git: Git) {
        git.fetch()
            .setRemote("origin")
            .setCredentialsProvider(null)
            .setTimeout(60)
            .call()
    }

    private fun inspectFetchedState(git: Git, checkedAt: Instant): RepositoryStateSnapshot {
        val repository = git.repository
        val branch = repository.branch
        val localHead = repository.resolve(Constants.HEAD)
        val remoteHead = repository.resolve("refs/remotes/origin/main")
        check(localHead != null) { "HEAD local no existe." }
        check(remoteHead != null) { "origin/main no existe después del fetch." }

        val relation = calculateRelation(repository, localHead, remoteHead)
        val changes = readWorkingTreeChanges(git)
        val type = if (changes.hasChanges) {
            RepositoryStateType.LOCAL_CHANGES
        } else {
            when (relation.relation) {
                CommitRelation.SYNCHRONIZED -> RepositoryStateType.SYNCHRONIZED
                CommitRelation.LOCAL_AHEAD -> RepositoryStateType.LOCAL_AHEAD
                CommitRelation.REMOTE_AHEAD -> RepositoryStateType.REMOTE_AHEAD
                CommitRelation.DIVERGED -> RepositoryStateType.DIVERGED
                CommitRelation.UNDETERMINED -> RepositoryStateType.ERROR
            }
        }
        return RepositoryStateSnapshot(
            type = type,
            relation = relation.relation,
            message = humanMessage(type, relation.ahead, relation.behind),
            branch = branch,
            localHead = localHead.name,
            remoteHead = remoteHead.name,
            ahead = relation.ahead,
            behind = relation.behind,
            changes = changes,
            checkedAt = checkedAt,
            remoteStateIsFresh = true,
        )
    }

    private fun blockedDownloadResult(state: RepositoryStateSnapshot): DownloadResult? {
        val outcomeAndMessage = when {
            state.type == RepositoryStateType.ERROR -> DownloadOutcome.ERROR to
                "No se ha podido comprobar GitHub con seguridad. No se realizó ningún cambio."
            state.changes.hasChanges -> DownloadOutcome.BLOCKED_LOCAL_CHANGES to
                "Hay cambios locales pendientes. No se descargará nada automáticamente."
            state.relation == CommitRelation.SYNCHRONIZED -> DownloadOutcome.ALREADY_SYNCHRONIZED to
                "El repositorio ya está sincronizado."
            state.relation == CommitRelation.LOCAL_AHEAD -> DownloadOutcome.BLOCKED_LOCAL_COMMITS to
                "Este dispositivo tiene commits que GitHub no tiene. No es seguro descargar automáticamente."
            state.relation == CommitRelation.DIVERGED -> DownloadOutcome.DIVERGED to
                "Hay cambios tanto en GitHub como en este dispositivo. Es necesario sincronizar."
            state.relation == CommitRelation.REMOTE_AHEAD && state.ahead == 0 && state.behind > 0 -> null
            else -> DownloadOutcome.ERROR to
                "El estado no permite un fast-forward seguro. No se realizó ningún cambio."
        } ?: return null
        return DownloadResult(
            outcome = outcomeAndMessage.first,
            message = outcomeAndMessage.second,
            previousHead = state.localHead,
            newHead = state.localHead,
            commitsDownloaded = 0,
            workingTreeClean = !state.changes.hasChanges,
            finalState = state,
            error = if (outcomeAndMessage.first == DownloadOutcome.ERROR) state.error else null,
        )
    }

    private fun downloadError(
        message: String,
        failure: Throwable? = null,
        finalState: RepositoryStateSnapshot? = null,
        outcome: DownloadOutcome = DownloadOutcome.ERROR,
    ) = DownloadResult(
        outcome = outcome,
        message = message,
        previousHead = finalState?.localHead,
        newHead = finalState?.localHead,
        commitsDownloaded = 0,
        workingTreeClean = finalState?.let {
            it.type != RepositoryStateType.ERROR && !it.changes.hasChanges
        } ?: false,
        finalState = finalState,
        error = failure?.let(::causeChain),
    )

    private fun calculateRelation(
        repository: Repository,
        localHead: ObjectId,
        remoteHead: ObjectId,
    ): CommitCounts {
        if (localHead == remoteHead) {
            return CommitCounts(CommitRelation.SYNCHRONIZED, ahead = 0, behind = 0)
        }
        val remoteIsAncestor = isMergedInto(repository, remoteHead, localHead)
        val localIsAncestor = isMergedInto(repository, localHead, remoteHead)
        val ahead = countExclusive(repository, localHead, remoteHead)
        val behind = countExclusive(repository, remoteHead, localHead)
        val relation = when {
            remoteIsAncestor && !localIsAncestor -> CommitRelation.LOCAL_AHEAD
            localIsAncestor && !remoteIsAncestor -> CommitRelation.REMOTE_AHEAD
            !remoteIsAncestor && !localIsAncestor -> CommitRelation.DIVERGED
            else -> CommitRelation.UNDETERMINED
        }
        return CommitCounts(relation, ahead, behind)
    }

    private fun isMergedInto(
        repository: Repository,
        possibleAncestor: ObjectId,
        tip: ObjectId,
    ): Boolean = RevWalk(repository).use { walk ->
        walk.isMergedInto(walk.parseCommit(possibleAncestor), walk.parseCommit(tip))
    }

    private fun countExclusive(
        repository: Repository,
        tip: ObjectId,
        excludedTip: ObjectId,
    ): Int = RevWalk(repository).use { walk ->
        walk.markStart(walk.parseCommit(tip))
        walk.markUninteresting(walk.parseCommit(excludedTip))
        walk.count()
    }

    private fun readWorkingTreeChanges(git: Git): WorkingTreeChanges {
        val status = git.status().call()
        return WorkingTreeChanges(
            newFiles = (status.untracked + status.added).toSortedSet(),
            modifiedFiles = (status.modified + status.changed).toSortedSet(),
            deletedFiles = (status.missing + status.removed).toSortedSet(),
            stagedFiles = (status.added + status.changed + status.removed).toSortedSet(),
            conflictingFiles = status.conflicting.toSortedSet(),
        )
    }

    private fun humanMessage(type: RepositoryStateType, ahead: Int, behind: Int): String = when (type) {
        RepositoryStateType.SYNCHRONIZED -> "Todo está sincronizado con GitHub."
        RepositoryStateType.LOCAL_CHANGES -> "Tienes cambios locales pendientes."
        RepositoryStateType.LOCAL_AHEAD -> "Tienes $ahead commit(s) pendientes de subir."
        RepositoryStateType.REMOTE_AHEAD -> "GitHub tiene $behind commit(s) nuevos."
        RepositoryStateType.DIVERGED -> "Hay cambios tanto en este dispositivo como en GitHub."
        RepositoryStateType.ERROR -> "No se ha podido determinar el estado con seguridad."
    }

    private fun errorSnapshot(
        checkedAt: Instant,
        message: String,
        failure: Throwable,
    ) = RepositoryStateSnapshot(
        type = RepositoryStateType.ERROR,
        relation = CommitRelation.UNDETERMINED,
        message = message,
        branch = null,
        localHead = null,
        remoteHead = null,
        ahead = 0,
        behind = 0,
        changes = WorkingTreeChanges(),
        checkedAt = checkedAt,
        remoteStateIsFresh = false,
        error = causeChain(failure),
    )

    private data class CommitCounts(
        val relation: CommitRelation,
        val ahead: Int,
        val behind: Int,
    )
}

private fun causeChain(failure: Throwable): String = generateSequence(failure) { it.cause }
    .take(5)
    .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message ?: "sin detalle"}" }
