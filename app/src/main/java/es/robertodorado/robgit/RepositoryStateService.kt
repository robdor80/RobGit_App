package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Arrays

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
    val authenticationRequired: Boolean = false,
    val authenticationRejected: Boolean = false,
    val repositoryAccessDenied: Boolean = false,
)

data class RepositoryPreparationResult(
    val success: Boolean,
    val repositoryPath: String,
    val cloned: Boolean,
    val message: String,
    val error: String? = null,
    val authenticationRequired: Boolean = false,
    val authenticationRejected: Boolean = false,
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

enum class UploadOutcome {
    SUCCESS,
    NOTHING_TO_UPLOAD,
    BLOCKED_REMOTE_AHEAD,
    BLOCKED_DIVERGED,
    BLOCKED_CONFLICTS,
    FETCH_ERROR,
    AUTH_REQUIRED,
    AUTH_FAILED,
    PUSH_REJECTED_REMOTE_CHANGED,
    PUSH_UNCERTAIN,
    ERROR,
}

data class UploadResult(
    val outcome: UploadOutcome,
    val message: String,
    val previousHead: String?,
    val attemptedHead: String?,
    val commitCreated: Boolean,
    val commitsUploaded: Int,
    val finalState: RepositoryStateSnapshot?,
    val error: String? = null,
    val authenticationRejected: Boolean = false,
)

enum class SynchronizationOutcome {
    SUCCESS_DOWNLOADED,
    SUCCESS_UPLOADED,
    NOTHING_TO_DO,
    BLOCKED_CHANGES_ON_BOTH_SIDES,
    BLOCKED_DIVERGED,
    BLOCKED_CONFLICTS,
    AUTH_REQUIRED,
    AUTH_FAILED,
    FETCH_ERROR,
    PUSH_REJECTED_REMOTE_CHANGED,
    PUSH_UNCERTAIN,
    ERROR,
}

data class SynchronizationResult(
    val outcome: SynchronizationOutcome,
    val message: String,
    val finalState: RepositoryStateSnapshot?,
    val downloadResult: DownloadResult? = null,
    val uploadResult: UploadResult? = null,
    val error: String? = null,
)

/** Small seam for testing credential delivery without invoking a remote repository. */
interface RepositoryGitEngine {
    fun prepare(repositoryDirectory: File, token: CharArray = charArrayOf()): RepositoryPreparationResult
    fun refreshState(repositoryDirectory: File, token: CharArray = charArrayOf()): RepositoryStateSnapshot
    fun downloadFastForward(repositoryDirectory: File, token: CharArray = charArrayOf()): DownloadResult
    /** Optional per-call diagnostics; existing engines keep the original contract. */
    fun downloadFastForward(repositoryDirectory: File, token: CharArray, performance: PullPerformanceRecorder): DownloadResult =
        downloadFastForward(repositoryDirectory, token)
    fun uploadSafely(repositoryDirectory: File, token: CharArray, commitMessage: String): UploadResult
    fun synchronizeSafely(repositoryDirectory: File, token: CharArray, commitMessage: String): SynchronizationResult
}

/** Safe Git operations scoped to one configured remote and branch. */
class RepositoryStateService(
    private val repositoryUrl: String,
    private val remoteGateway: RepositoryRemoteGateway = JGitRepositoryRemoteGateway(),
    private val branch: String = "main",
) : RepositoryGitEngine {
    private val remoteTrackingRef: String get() = "refs/remotes/origin/$branch"

    fun isPrepared(repositoryDirectory: File): Boolean = try {
        File(repositoryDirectory, ".git").isDirectory &&
            Git.open(repositoryDirectory).use { git ->
                validateRepositoryIdentity(git)
                git.repository.exactRef(remoteTrackingRef) != null
            }
    } catch (_: Exception) {
        false
    }

    override fun prepare(repositoryDirectory: File, token: CharArray): RepositoryPreparationResult {
        var temporaryDirectory: File? = null
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
                    check(git.repository.branch == branch && git.repository.exactRef(remoteTrackingRef) != null) {
                        "La rama configurada no existe en el repositorio existente."
                    }
                }
                return RepositoryPreparationResult(
                    success = true,
                    repositoryPath = repositoryDirectory.absolutePath,
                    cloned = false,
                    message = "Repositorio persistente abierto.",
                )
            }

            repositoryDirectory.parentFile?.mkdirs()
            val stagingDirectory = File(repositoryDirectory.parentFile, ".${repositoryDirectory.name}.preparing-${java.util.UUID.randomUUID()}")
            temporaryDirectory = stagingDirectory
            val credentials = if (token.isNotEmpty()) githubJGitCredentials(token) else null
            try {
                Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(stagingDirectory)
                    .setBranch("refs/heads/$branch")
                    .setCredentialsProvider(credentials)
                    .setTimeout(60)
                    .call()
                    .use { git ->
                        check(git.repository.branch == branch && git.repository.exactRef(remoteTrackingRef) != null) {
                            "La rama configurada no existe después del clone."
                        }
                    }
            } finally {
                credentials?.clear()
            }
            Files.move(stagingDirectory.toPath(), repositoryDirectory.toPath())
            temporaryDirectory = null
            check(isPrepared(repositoryDirectory)) { "El repositorio clonado no pudo verificarse en su carpeta definitiva." }
            return RepositoryPreparationResult(
                success = true,
                repositoryPath = repositoryDirectory.absolutePath,
                cloned = true,
                message = "Repositorio persistente clonado.",
            )
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            val missingBranch = generateSequence(failure) { it.cause }.take(5).any {
                it.javaClass.simpleName.contains("RefNotFound") ||
                    it.message.orEmpty().contains("rama configurada no existe", ignoreCase = true) ||
                    it.message.orEmpty().contains("branch not found", ignoreCase = true) ||
                    (it.message.orEmpty().contains("refs/heads/$branch") &&
                        (it.message.orEmpty().contains("does not have", ignoreCase = true) ||
                            it.message.orEmpty().contains("not found", ignoreCase = true) ||
                            it.message.orEmpty().contains("no such", ignoreCase = true)))
            }
            val authFailure = !missingBranch && (isAuthenticationFailure(failure) ||
                token.isEmpty() && generateSequence(failure) { it.cause }.take(5).any {
                    val detail = it.message.orEmpty().lowercase()
                    "repository not found" in detail || "404" in detail
                })
            return RepositoryPreparationResult(
                success = false,
                repositoryPath = repositoryDirectory.absolutePath,
                cloned = false,
                message = when {
                    authFailure && token.isEmpty() -> "Conecta RobGit con GitHub desde Ajustes para acceder a este repositorio."
                    authFailure && isExplicitAuthenticationRejection(failure) -> "Es necesario volver a conectar GitHub."
                    authFailure -> "RobGit no tiene acceso a este repositorio desde GitHub."
                    missingBranch -> "La rama configurada $branch no existe en este repositorio."
                    else -> "No se pudo preparar el repositorio."
                },
                error = safeFailure("Preparación del repositorio", failure),
                authenticationRequired = authFailure,
                authenticationRejected = token.isNotEmpty() && isExplicitAuthenticationRejection(failure),
            )
        } finally {
            temporaryDirectory?.let { temp ->
                if (temp.canonicalFile.parentFile == repositoryDirectory.parentFile?.canonicalFile &&
                    temp.name.startsWith(".${repositoryDirectory.name}.preparing-")) {
                    temp.deleteRecursively()
                }
            }
            Arrays.fill(token, '\u0000')
        }
    }

    /** Fetches origin and then calculates state without changing HEAD or working-tree files. */
    override fun refreshState(repositoryDirectory: File, token: CharArray): RepositoryStateSnapshot {
        val checkedAt = Instant.now()
        val credentials = if (token.isNotEmpty()) githubJGitCredentials(token) else null
        try {
            check(File(repositoryDirectory, ".git").isDirectory) {
                "El repositorio persistente todavía no está preparado."
            }
            Git.open(repositoryDirectory).use { git ->
                validateFunctionalRepository(git)

                try {
                    fetchOrigin(git, credentials)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    return errorSnapshot(
                        checkedAt,
                        "No se pudo actualizar el estado desde GitHub. El estado remoto no está verificado.",
                        failure,
                        authenticationRequired = isAuthenticationFailure(failure),
                        authenticationRejected = token.isNotEmpty() && isExplicitAuthenticationRejection(failure),
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
        } finally {
            credentials?.clear()
            Arrays.fill(token, '\u0000')
        }
    }

    /** Revalidates with fetch and applies only a verified fast-forward of main. */
    override fun downloadFastForward(repositoryDirectory: File, token: CharArray): DownloadResult =
        downloadFastForwardMeasured(repositoryDirectory, token, null)

    override fun downloadFastForward(repositoryDirectory: File, token: CharArray, performance: PullPerformanceRecorder): DownloadResult =
        downloadFastForwardMeasured(repositoryDirectory, token, performance)

    private fun downloadFastForwardMeasured(
        repositoryDirectory: File, token: CharArray, performance: PullPerformanceRecorder?,
    ): DownloadResult {
        val checkedAt = Instant.now()
        val credentials = performance.measure(PullPhase.JGIT_CREDENTIALS) {
            if (token.isNotEmpty()) githubJGitCredentials(token) else null
        }
        try {
            performance.measure(PullPhase.OPEN_REPOSITORY) {
                check(File(repositoryDirectory, ".git").isDirectory) {
                    "El repositorio persistente todavía no está preparado."
                }
                Git.open(repositoryDirectory)
            }.use { git ->
                performance.measure(PullPhase.VALIDATE_REPOSITORY) { validateFunctionalRepository(git) }
                try {
                    performance.measure(PullPhase.FETCH) { fetchOrigin(git, credentials) }
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    val failureState = errorSnapshot(
                        checkedAt,
                        "No se pudo actualizar el estado desde GitHub. El estado remoto no está verificado.",
                        failure,
                        authenticationRequired = isAuthenticationFailure(failure),
                        authenticationRejected = token.isNotEmpty() && isExplicitAuthenticationRejection(failure),
                    )
                    return downloadError(
                        "No se ha podido comprobar GitHub con seguridad. No se realizó ningún cambio.",
                        failure,
                        finalState = failureState,
                        outcome = DownloadOutcome.FETCH_ERROR,
                    )
                }

                val state = inspectFetchedState(git, checkedAt, performance, PullInspection.INITIAL)
                val blocked = performance.measure(PullPhase.INITIAL_POLICY) { blockedDownloadResult(state) }
                if (blocked != null) return blocked

                val repository = git.repository
                val (previousHead, fetchedRemoteHead) = performance.measure(PullPhase.BASELINE_REFS) {
                    val previous = repository.resolve(Constants.HEAD)
                    val remote = repository.resolve(remoteTrackingRef)
                    check(previous != null && remote != null)
                    previous to remote
                }

                // Guard again immediately before MergeCommand in case local files or refs changed.
                val immediateChanges = readWorkingTreeChanges(git, performance, PullPhase.GUARD_STATUS, PullPhase.GUARD_CHANGES)
                val immediateLocalHead = performance.measure(PullPhase.GUARD_HEAD) { repository.resolve(Constants.HEAD) }
                val immediateRemoteHead = performance.measure(PullPhase.GUARD_REMOTE_REF) { repository.resolve(remoteTrackingRef) }
                val changed = performance.measure(PullPhase.GUARD_VALIDATE) {
                    immediateChanges.hasChanges || immediateLocalHead != previousHead ||
                        immediateRemoteHead != fetchedRemoteHead
                }
                if (changed) {
                    val latest = inspectFetchedState(git, Instant.now(), performance, PullInspection.RECHECK)
                    return blockedDownloadResult(latest) ?: downloadError(
                        "El repositorio cambió durante la comprobación. No se realizó ningún cambio.",
                    )
                }

                val mergeResult = performance.measure(PullPhase.FAST_FORWARD) {
                    git.merge()
                        .include(fetchedRemoteHead)
                        .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                        .setCommit(false)
                        .call()
                }

                if (mergeResult.mergeStatus != MergeResult.MergeStatus.FAST_FORWARD) {
                    val after = inspectFetchedState(git, Instant.now(), performance, PullInspection.RECHECK)
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

                val finalState = inspectFetchedState(git, Instant.now(), performance, PullInspection.FINAL)
                val newHead = performance.measure(PullPhase.FINAL_VALIDATE) {
                    val finalHead = repository.resolve(Constants.HEAD)
                    val finalRemoteHead = repository.resolve(remoteTrackingRef)
                    check(finalHead == fetchedRemoteHead) {
                        "HEAD no coincide con el commit remoto obtenido mediante fetch."
                    }
                    check(finalRemoteHead == fetchedRemoteHead) {
                        "$remoteTrackingRef cambió durante la operación."
                    }
                    check(finalState.type == RepositoryStateType.SYNCHRONIZED)
                    check(finalState.ahead == 0 && finalState.behind == 0)
                    check(!finalState.changes.hasChanges) { "El working tree final no está limpio." }
                    finalHead
                }

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
        } finally {
            performance.measure(PullPhase.ENGINE_CREDENTIAL_CLEANUP) {
                credentials?.clear()
                Arrays.fill(token, '\u0000')
            }
        }
    }

    /** Revalidates origin, commits pending files once, and performs one normal non-forced push. */
    override fun uploadSafely(
        repositoryDirectory: File,
        token: CharArray,
        commitMessage: String,
    ): UploadResult {
        val checkedAt = Instant.now()
        var credentials: UsernamePasswordCredentialsProvider? = null
        var previousHead: ObjectId? = null
        var attemptedHead: ObjectId? = null
        var commitCreated = false
        try {
            check(File(repositoryDirectory, ".git").isDirectory) {
                "El repositorio persistente todavía no está preparado."
            }
            Git.open(repositoryDirectory).use { git ->
                validateRepositoryIdentity(git)
                val statusBeforeFetch = git.status().call()
                if (statusBeforeFetch.conflicting.isNotEmpty()) {
                    return uploadResult(
                        outcome = UploadOutcome.BLOCKED_CONFLICTS,
                        message = "Hay conflictos locales. No se preparó ningún commit ni se intentó subir.",
                        git = git,
                    )
                }
                check(git.repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
                    "El repositorio tiene una operación Git incompleta (${git.repository.repositoryState})."
                }
                if (token.isEmpty()) {
                    return uploadResult(
                        outcome = UploadOutcome.AUTH_REQUIRED,
                        message = "Conecta RobGit con GitHub desde Ajustes para subir.",
                        git = git,
                    )
                }
                val normalizedMessage = commitMessage.trim()
                if (normalizedMessage.isEmpty()) {
                    return uploadResult(
                        outcome = UploadOutcome.ERROR,
                        message = "El mensaje del cambio no puede estar vacío.",
                        git = git,
                    )
                }

                credentials = githubJGitCredentials(token)
                try {
                    remoteGateway.fetch(git, credentials)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    return if (isAuthenticationFailure(failure)) {
                        uploadResult(
                            outcome = UploadOutcome.AUTH_FAILED,
                            message = "GitHub rechazó las credenciales. No se modificó el trabajo local.",
                            git = git,
                            authenticationRejected = isExplicitAuthenticationRejection(failure),
                        )
                    } else {
                        uploadResult(
                            outcome = UploadOutcome.FETCH_ERROR,
                            message = "No se pudo comprobar GitHub. No se preparó ningún commit ni se intentó subir.",
                            git = git,
                            error = safeFailure("Fetch inicial", failure),
                        )
                    }
                }

                val initialState = inspectFetchedState(git, checkedAt)
                when (initialState.relation) {
                    CommitRelation.REMOTE_AHEAD -> return uploadResult(
                        outcome = UploadOutcome.BLOCKED_REMOTE_AHEAD,
                        message = "GitHub tiene cambios nuevos. Descárgalos antes de subir.",
                        git = git,
                        finalState = initialState,
                    )
                    CommitRelation.DIVERGED -> return uploadResult(
                        outcome = UploadOutcome.BLOCKED_DIVERGED,
                        message = "El repositorio local y GitHub han divergido. No se realizó ninguna mutación.",
                        git = git,
                        finalState = initialState,
                    )
                    CommitRelation.UNDETERMINED -> return uploadResult(
                        outcome = UploadOutcome.ERROR,
                        message = "No se puede determinar con seguridad la relación con GitHub.",
                        git = git,
                        finalState = initialState,
                    )
                    CommitRelation.SYNCHRONIZED,
                    CommitRelation.LOCAL_AHEAD,
                    -> Unit
                }
                if (initialState.changes.conflictingFiles.isNotEmpty()) {
                    return uploadResult(
                        outcome = UploadOutcome.BLOCKED_CONFLICTS,
                        message = "Hay conflictos locales. No se preparó ningún commit ni se intentó subir.",
                        git = git,
                        finalState = initialState,
                    )
                }

                previousHead = git.repository.resolve(Constants.HEAD)
                val baselineRemote = git.repository.resolve(remoteTrackingRef)
                check(previousHead != null && baselineRemote != null)

                if (initialState.changes.hasChanges) {
                    stageAllRelevantChanges(git)
                    val staged = git.status().call()
                    check(
                        staged.added.isNotEmpty() || staged.changed.isNotEmpty() ||
                            staged.removed.isNotEmpty()
                    ) { "No hay cambios relevantes preparados para crear el commit." }
                    val identity = commitIdentity(git.repository)
                    val commit = git.commit()
                        .setMessage(normalizedMessage)
                        .setAuthor(identity)
                        .setCommitter(identity)
                        .call()
                    commitCreated = true
                    attemptedHead = commit.id
                    check(git.repository.resolve(Constants.HEAD) == commit.id)
                    check(git.status().call().isClean) {
                        "El working tree no quedó limpio después de crear el commit."
                    }
                } else {
                    attemptedHead = previousHead
                }

                if (attemptedHead == baselineRemote) {
                    return uploadResult(
                        outcome = UploadOutcome.NOTHING_TO_UPLOAD,
                        message = "No hay cambios ni commits pendientes de subir.",
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                    )
                }

                try {
                    remoteGateway.fetch(git, credentials)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    val outcome = if (isAuthenticationFailure(failure)) {
                        UploadOutcome.AUTH_FAILED
                    } else {
                        UploadOutcome.FETCH_ERROR
                    }
                    return uploadResult(
                        outcome = outcome,
                        message = savedWorkMessage(
                            if (outcome == UploadOutcome.AUTH_FAILED) {
                                "GitHub rechazó las credenciales antes del push."
                            } else {
                                "No se pudo volver a comprobar GitHub antes del push."
                            },
                            commitCreated,
                        ),
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                        commitCreated = commitCreated,
                        error = if (outcome == UploadOutcome.FETCH_ERROR) {
                            safeFailure("Segundo fetch", failure)
                        } else {
                            null
                        },
                        authenticationRejected = outcome == UploadOutcome.AUTH_FAILED && isExplicitAuthenticationRejection(failure),
                    )
                }

                val remoteBeforePush = git.repository.resolve(remoteTrackingRef)
                check(remoteBeforePush != null)
                if (remoteBeforePush != baselineRemote) {
                    val latest = inspectFetchedState(git, Instant.now())
                    return uploadResult(
                        outcome = UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED,
                        message = savedWorkMessage(
                            "GitHub cambió mientras se preparaba la subida. No se intentó hacer push.",
                            commitCreated,
                        ),
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                        commitCreated = commitCreated,
                        finalState = latest,
                    )
                }

                val relationBeforePush = calculateRelation(
                    git.repository,
                    requireNotNull(attemptedHead),
                    remoteBeforePush,
                )
                check(
                    relationBeforePush.relation == CommitRelation.LOCAL_AHEAD &&
                        relationBeforePush.ahead > 0 && relationBeforePush.behind == 0
                ) { "El estado dejó de ser un avance local seguro antes del push." }
                val commitsToUpload = relationBeforePush.ahead

                val pushResult = try {
                    remoteGateway.pushBranch(git, requireNotNull(credentials), branch)
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    if (isAuthenticationFailure(failure)) {
                        return uploadResult(
                            outcome = UploadOutcome.AUTH_FAILED,
                            message = savedWorkMessage(
                                "GitHub rechazó las credenciales durante el push.",
                                commitCreated,
                            ),
                            git = git,
                            previousHead = previousHead,
                            attemptedHead = attemptedHead,
                            commitCreated = commitCreated,
                            authenticationRejected = isExplicitAuthenticationRejection(failure),
                        )
                    }
                    return verifyAmbiguousPushOnce(
                        git = git,
                        credentials = requireNotNull(credentials),
                        previousHead = requireNotNull(previousHead),
                        attemptedHead = requireNotNull(attemptedHead),
                        commitCreated = commitCreated,
                        commitsToUpload = commitsToUpload,
                        failure = failure,
                    )
                }

                return when (pushResult.outcome) {
                    PushTransportOutcome.ACCEPTED -> finalizeAcceptedPush(
                        git = git,
                        credentials = requireNotNull(credentials),
                        previousHead = requireNotNull(previousHead),
                        attemptedHead = requireNotNull(attemptedHead),
                        commitCreated = commitCreated,
                        commitsToUpload = commitsToUpload,
                    )
                    PushTransportOutcome.REJECTED_REMOTE_CHANGED -> uploadResult(
                        outcome = UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED,
                        message = savedWorkMessage(
                            "GitHub cambió y rechazó el push normal. No se forzó la subida.",
                            commitCreated,
                        ),
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                        commitCreated = commitCreated,
                    )
                    PushTransportOutcome.AUTH_FAILED -> uploadResult(
                        outcome = UploadOutcome.AUTH_FAILED,
                        message = savedWorkMessage(
                            "GitHub rechazó las credenciales durante el push.",
                            commitCreated,
                        ),
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                        commitCreated = commitCreated,
                        authenticationRejected = true,
                    )
                    PushTransportOutcome.AMBIGUOUS -> verifyAmbiguousPushOnce(
                        git = git,
                        credentials = requireNotNull(credentials),
                        previousHead = requireNotNull(previousHead),
                        attemptedHead = requireNotNull(attemptedHead),
                        commitCreated = commitCreated,
                        commitsToUpload = commitsToUpload,
                    )
                    PushTransportOutcome.ERROR -> uploadResult(
                        outcome = UploadOutcome.ERROR,
                        message = savedWorkMessage(
                            "GitHub devolvió un resultado de push no aceptado.",
                            commitCreated,
                        ),
                        git = git,
                        previousHead = previousHead,
                        attemptedHead = attemptedHead,
                        commitCreated = commitCreated,
                        error = "Resultado remoto no aceptado.",
                    )
                }
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            return UploadResult(
                outcome = UploadOutcome.ERROR,
                message = savedWorkMessage(
                    "No se pudo completar SUBIR con seguridad.",
                    commitCreated,
                ),
                previousHead = previousHead?.name,
                attemptedHead = attemptedHead?.name,
                commitCreated = commitCreated,
                commitsUploaded = 0,
                finalState = null,
                error = safeFailure("SUBIR", failure),
            )
        } finally {
            credentials?.clear()
            Arrays.fill(token, '\u0000')
        }
    }

    /** Chooses at most one already-validated operation: no action, fast-forward download, or upload. */
    override fun synchronizeSafely(
        repositoryDirectory: File,
        token: CharArray,
        commitMessage: String,
    ): SynchronizationResult {
        try {
            val conflictCheck = readLocalConflicts(repositoryDirectory)
            if (conflictCheck.hasConflicts) {
                return SynchronizationResult(
                    outcome = SynchronizationOutcome.BLOCKED_CONFLICTS,
                    message = "Hay conflictos Git existentes. RobGit no realizará ninguna operación automática.",
                    finalState = conflictCheck.state,
                )
            }
            if (conflictCheck.error != null) {
                return SynchronizationResult(
                    outcome = SynchronizationOutcome.ERROR,
                    message = "No se ha podido abrir el repositorio con seguridad.",
                    finalState = null,
                    error = conflictCheck.error,
                )
            }

            // refreshState performs the mandatory initial fetch and graph recalculation.
            val state = refreshState(repositoryDirectory, token.copyOf())
            if (state.type == RepositoryStateType.ERROR) {
                return SynchronizationResult(
                    outcome = if (state.authenticationRequired) {
                        SynchronizationOutcome.AUTH_REQUIRED
                    } else if (state.remoteStateIsFresh) {
                        SynchronizationOutcome.ERROR
                    } else {
                        SynchronizationOutcome.FETCH_ERROR
                    },
                    message = state.message,
                    finalState = state,
                    error = state.error,
                )
            }

            return when (state.relation) {
                CommitRelation.SYNCHRONIZED -> {
                    if (state.changes.hasChanges) {
                        mapUploadForSynchronization(
                            uploadSafely(repositoryDirectory, token, commitMessage),
                        )
                    } else {
                        SynchronizationResult(
                            outcome = SynchronizationOutcome.NOTHING_TO_DO,
                            message = "Ya está sincronizado.",
                            finalState = state,
                        )
                    }
                }
                CommitRelation.REMOTE_AHEAD -> {
                    if (state.changes.hasChanges) {
                        SynchronizationResult(
                            outcome = SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES,
                            message = "Hay cambios tanto en este dispositivo como en GitHub. " +
                                "RobGit no realizará ninguna operación automática.",
                            finalState = state,
                        )
                    } else {
                        mapDownloadForSynchronization(downloadFastForward(repositoryDirectory, token.copyOf()))
                    }
                }
                CommitRelation.LOCAL_AHEAD -> mapUploadForSynchronization(
                    uploadSafely(repositoryDirectory, token, commitMessage),
                )
                CommitRelation.DIVERGED -> SynchronizationResult(
                    outcome = SynchronizationOutcome.BLOCKED_DIVERGED,
                    message = "El repositorio local y GitHub han divergido. " +
                        "RobGit no realizará ninguna operación automática.",
                    finalState = state,
                )
                CommitRelation.UNDETERMINED -> SynchronizationResult(
                    outcome = SynchronizationOutcome.ERROR,
                    message = "No se puede determinar con seguridad la relación con GitHub.",
                    finalState = state,
                    error = state.error,
                )
            }
        } finally {
            // uploadSafely also clears it; double clearing is intentional and harmless.
            Arrays.fill(token, '\u0000')
        }
    }

    private fun mapDownloadForSynchronization(download: DownloadResult): SynchronizationResult =
        SynchronizationResult(
            outcome = when (download.outcome) {
                DownloadOutcome.SUCCESS -> SynchronizationOutcome.SUCCESS_DOWNLOADED
                DownloadOutcome.ALREADY_SYNCHRONIZED -> SynchronizationOutcome.NOTHING_TO_DO
                DownloadOutcome.FETCH_ERROR -> if (download.finalState?.authenticationRequired == true) {
                    SynchronizationOutcome.AUTH_REQUIRED
                } else {
                    SynchronizationOutcome.FETCH_ERROR
                }
                DownloadOutcome.DIVERGED -> SynchronizationOutcome.BLOCKED_DIVERGED
                DownloadOutcome.BLOCKED_LOCAL_CHANGES -> {
                    SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES
                }
                else -> SynchronizationOutcome.ERROR
            },
            message = download.message,
            finalState = download.finalState,
            downloadResult = download,
            error = download.error,
        )

    private fun mapUploadForSynchronization(upload: UploadResult): SynchronizationResult =
        SynchronizationResult(
            outcome = when (upload.outcome) {
                UploadOutcome.SUCCESS -> SynchronizationOutcome.SUCCESS_UPLOADED
                UploadOutcome.NOTHING_TO_UPLOAD -> SynchronizationOutcome.NOTHING_TO_DO
                UploadOutcome.AUTH_REQUIRED -> SynchronizationOutcome.AUTH_REQUIRED
                UploadOutcome.AUTH_FAILED -> SynchronizationOutcome.AUTH_FAILED
                UploadOutcome.FETCH_ERROR -> SynchronizationOutcome.FETCH_ERROR
                UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED -> {
                    SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED
                }
                UploadOutcome.PUSH_UNCERTAIN -> SynchronizationOutcome.PUSH_UNCERTAIN
                UploadOutcome.BLOCKED_DIVERGED -> SynchronizationOutcome.BLOCKED_DIVERGED
                UploadOutcome.BLOCKED_CONFLICTS -> SynchronizationOutcome.BLOCKED_CONFLICTS
                UploadOutcome.BLOCKED_REMOTE_AHEAD,
                UploadOutcome.ERROR,
                -> SynchronizationOutcome.ERROR
            },
            message = upload.message,
            finalState = upload.finalState,
            uploadResult = upload,
            error = upload.error,
        )

    private fun readLocalConflicts(repositoryDirectory: File): LocalConflictCheck = try {
        check(File(repositoryDirectory, ".git").isDirectory) {
            "El repositorio persistente todavía no está preparado."
        }
        Git.open(repositoryDirectory).use { git ->
            validateRepositoryIdentity(git)
            val hasConflicts = git.status().call().conflicting.isNotEmpty()
            LocalConflictCheck(hasConflicts = hasConflicts)
        }
    } catch (failure: Throwable) {
        if (failure !is Exception && failure !is LinkageError) throw failure
        LocalConflictCheck(error = safeFailure("Apertura del repositorio", failure))
    }

    private data class LocalConflictCheck(
        val hasConflicts: Boolean = false,
        val state: RepositoryStateSnapshot? = null,
        val error: String? = null,
    )

    private fun stageAllRelevantChanges(git: Git) {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
    }

    private fun commitIdentity(repository: Repository): PersonIdent {
        val configuredName = repository.config.getString("user", null, "name")?.trim()
        val configuredEmail = repository.config.getString("user", null, "email")?.trim()
        return if (!configuredName.isNullOrEmpty() &&
            !configuredEmail.isNullOrEmpty() && "@" in configuredEmail
        ) {
            PersonIdent(configuredName, configuredEmail)
        } else {
            PersonIdent("RobGit Android", "robgit@localhost")
        }
    }

    private fun verifyAmbiguousPushOnce(
        git: Git,
        credentials: CredentialsProvider,
        previousHead: ObjectId,
        attemptedHead: ObjectId,
        commitCreated: Boolean,
        commitsToUpload: Int,
        failure: Throwable? = null,
    ): UploadResult {
        return try {
            // This is the only remote query after an ambiguous push. Never retry the push.
            remoteGateway.fetch(git, credentials)
            val remoteHead = git.repository.resolve(remoteTrackingRef)
            if (remoteHead == attemptedHead) {
                val finalState = inspectFetchedState(git, Instant.now())
                uploadResult(
                    outcome = UploadOutcome.SUCCESS,
                    message = "La subida quedó confirmada mediante una única comprobación posterior.",
                    git = git,
                    previousHead = previousHead,
                    attemptedHead = attemptedHead,
                    commitCreated = commitCreated,
                    commitsUploaded = commitsToUpload,
                    finalState = finalState,
                )
            } else {
                uploadResult(
                    outcome = UploadOutcome.PUSH_UNCERTAIN,
                    message = savedWorkMessage(
                        "No se pudo confirmar que GitHub apunte al commit intentado. No se repetirá el push.",
                        commitCreated,
                    ),
                    git = git,
                    previousHead = previousHead,
                    attemptedHead = attemptedHead,
                    commitCreated = commitCreated,
                    finalState = inspectFetchedState(git, Instant.now()),
                    error = failure?.let { safeFailure("Respuesta de push ambigua", it) },
                )
            }
        } catch (verificationFailure: Throwable) {
            if (verificationFailure !is Exception && verificationFailure !is LinkageError) {
                throw verificationFailure
            }
            uploadResult(
                outcome = UploadOutcome.PUSH_UNCERTAIN,
                message = savedWorkMessage(
                    "No se pudo confirmar si GitHub recibió el commit. No se repetirá el push.",
                    commitCreated,
                ),
                git = git,
                previousHead = previousHead,
                attemptedHead = attemptedHead,
                commitCreated = commitCreated,
                error = safeFailure("Verificación única posterior al push", verificationFailure),
            )
        }
    }

    private fun finalizeAcceptedPush(
        git: Git,
        credentials: CredentialsProvider,
        previousHead: ObjectId,
        attemptedHead: ObjectId,
        commitCreated: Boolean,
        commitsToUpload: Int,
    ): UploadResult {
        return try {
            remoteGateway.fetch(git, credentials)
            val finalState = inspectFetchedState(git, Instant.now())
            val finalRemote = git.repository.resolve(remoteTrackingRef)
            check(finalRemote != null)
            val attemptedIsPresent = finalRemote == attemptedHead ||
                isMergedInto(git.repository, attemptedHead, finalRemote)
            if (!attemptedIsPresent) {
                uploadResult(
                    outcome = UploadOutcome.PUSH_UNCERTAIN,
                    message = savedWorkMessage(
                        "El push respondió como aceptado, pero no se pudo verificar el commit en GitHub.",
                        commitCreated,
                    ),
                    git = git,
                    previousHead = previousHead,
                    attemptedHead = attemptedHead,
                    commitCreated = commitCreated,
                    finalState = finalState,
                )
            } else {
                val message = if (finalRemote == attemptedHead) {
                    "Subida confirmada. El repositorio está sincronizado con GitHub."
                } else {
                    "Subida confirmada. GitHub volvió a avanzar después de recibir el commit."
                }
                uploadResult(
                    outcome = UploadOutcome.SUCCESS,
                    message = message,
                    git = git,
                    previousHead = previousHead,
                    attemptedHead = attemptedHead,
                    commitCreated = commitCreated,
                    commitsUploaded = commitsToUpload,
                    finalState = finalState,
                )
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            uploadResult(
                outcome = UploadOutcome.SUCCESS,
                message = "El servidor aceptó la subida, pero no se pudo actualizar el estado final.",
                git = git,
                previousHead = previousHead,
                attemptedHead = attemptedHead,
                commitCreated = commitCreated,
                commitsUploaded = commitsToUpload,
                error = safeFailure("Verificación final", failure),
            )
        }
    }

    private fun uploadResult(
        outcome: UploadOutcome,
        message: String,
        git: Git,
        previousHead: ObjectId? = git.repository.resolve(Constants.HEAD),
        attemptedHead: ObjectId? = git.repository.resolve(Constants.HEAD),
        commitCreated: Boolean = false,
        commitsUploaded: Int = 0,
        finalState: RepositoryStateSnapshot? = null,
        error: String? = null,
        authenticationRejected: Boolean = false,
    ) = UploadResult(
        outcome = outcome,
        message = message,
        previousHead = previousHead?.name,
        attemptedHead = attemptedHead?.name,
        commitCreated = commitCreated,
        commitsUploaded = commitsUploaded,
        finalState = finalState,
        error = error,
        authenticationRejected = authenticationRejected,
    )

    private fun savedWorkMessage(message: String, commitCreated: Boolean): String =
        if (commitCreated) {
            "$message El trabajo está guardado localmente; no se ha perdido."
        } else {
            message
        }

    private fun isAuthenticationFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(5).any {
            val text = "${it.javaClass.name} ${it.message.orEmpty()}".lowercase()
            "not authorized" in text || "authentication" in text ||
                "unauthorized" in text || "401" in text || "403" in text ||
                "repository not found" in text || "404" in text
        }

    private fun isExplicitAuthenticationRejection(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(5).any {
            val text = "${it.javaClass.name} ${it.message.orEmpty()}".lowercase()
            "401" in text || "bad credentials" in text || "invalid credentials" in text ||
                "not authorized" in text || "unauthorized" in text
        }

    /** Returns diagnostic classes only; exception messages can contain credential material. */
    private fun safeFailure(stage: String, failure: Throwable): String =
        "$stage — " + generateSequence(failure) { it.cause }
            .take(5)
            .joinToString(" ← ") { it.javaClass.name }

    private fun validateRepositoryIdentity(git: Git) {
        val repository = git.repository
        check(!repository.isBare && repository.objectDatabase.exists()) {
            "El repositorio persistente no es válido."
        }
        check(repository.branch == branch) {
            "Se esperaba la rama $branch, se obtuvo ${repository.branch}."
        }
        val origin = repository.config.getString("remote", "origin", "url")
        check(origin != null && URIish(origin) == URIish(repositoryUrl)) {
            "El repositorio utiliza un remoto distinto del esperado."
        }
    }

    private fun validateFunctionalRepository(git: Git) {
        validateRepositoryIdentity(git)
        val repository = git.repository
        check(repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
            "El repositorio tiene una operación Git incompleta (${repository.repositoryState})."
        }
    }

    private fun fetchOrigin(git: Git, credentials: CredentialsProvider?) {
        remoteGateway.fetch(git, credentials)
    }

    private enum class PullInspection(
        val refs: PullPhase, val graph: PullPhase, val status: PullPhase, val changes: PullPhase,
    ) {
        INITIAL(PullPhase.INITIAL_REFS, PullPhase.INITIAL_GRAPH, PullPhase.INITIAL_STATUS, PullPhase.INITIAL_CHANGES),
        FINAL(PullPhase.FINAL_REFS, PullPhase.FINAL_GRAPH, PullPhase.FINAL_STATUS, PullPhase.FINAL_CHANGES),
        RECHECK(PullPhase.RECHECK_REFS, PullPhase.RECHECK_GRAPH, PullPhase.RECHECK_STATUS, PullPhase.RECHECK_CHANGES),
    }

    private fun inspectFetchedState(git: Git, checkedAt: Instant,
                                    performance: PullPerformanceRecorder? = null,
                                    inspection: PullInspection = PullInspection.INITIAL): RepositoryStateSnapshot {
        val repository = git.repository
        val (branch, localHead, remoteHead) = performance.measure(inspection.refs) {
            val currentBranch = repository.branch
            val local = repository.resolve(Constants.HEAD)
            val remote = repository.resolve(remoteTrackingRef)
            check(local != null) { "HEAD local no existe." }
            check(remote != null) { "$remoteTrackingRef no existe después del fetch." }
            Triple(currentBranch, local, remote)
        }

        val relation = performance.measure(inspection.graph) { calculateRelation(repository, localHead, remoteHead, performance) }
        val changes = readWorkingTreeChanges(git, performance, inspection.status, inspection.changes)
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
        performance: PullPerformanceRecorder? = null,
    ): CommitCounts {
        if (localHead == remoteHead) {
            return CommitCounts(CommitRelation.SYNCHRONIZED, ahead = 0, behind = 0)
        }
        val remoteIsAncestor = isMergedInto(repository, remoteHead, localHead, performance)
        val localIsAncestor = isMergedInto(repository, localHead, remoteHead, performance)
        val ahead = countExclusive(repository, localHead, remoteHead, performance)
        val behind = countExclusive(repository, remoteHead, localHead, performance)
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
        performance: PullPerformanceRecorder? = null,
    ): Boolean {
        performance?.count(PullCounter.REV_WALK_CALLS)
        return RevWalk(repository).use { walk ->
            walk.isMergedInto(walk.parseCommit(possibleAncestor), walk.parseCommit(tip))
        }
    }

    private fun countExclusive(
        repository: Repository,
        tip: ObjectId,
        excludedTip: ObjectId,
        performance: PullPerformanceRecorder? = null,
    ): Int {
        performance?.count(PullCounter.REV_WALK_CALLS)
        return RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(tip))
            walk.markUninteresting(walk.parseCommit(excludedTip))
            walk.count()
        }
    }

    private fun readWorkingTreeChanges(git: Git, performance: PullPerformanceRecorder? = null,
                                       statusPhase: PullPhase = PullPhase.INITIAL_STATUS,
                                       changesPhase: PullPhase = PullPhase.INITIAL_CHANGES): WorkingTreeChanges {
        val status = performance.measure(statusPhase) {
            performance?.count(PullCounter.STATUS_CALLS)
            git.status().call()
        }
        return performance.measure(changesPhase) {
            WorkingTreeChanges(
                newFiles = (status.untracked + status.added).toSortedSet(),
                modifiedFiles = (status.modified + status.changed).toSortedSet(),
                deletedFiles = (status.missing + status.removed).toSortedSet(),
                stagedFiles = (status.added + status.changed + status.removed).toSortedSet(),
                conflictingFiles = status.conflicting.toSortedSet(),
            )
        }
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
        authenticationRequired: Boolean = false,
        authenticationRejected: Boolean = false,
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
        authenticationRequired = authenticationRequired,
        authenticationRejected = authenticationRejected,
    )

    private data class CommitCounts(
        val relation: CommitRelation,
        val ahead: Int,
        val behind: Int,
    )
}

private fun causeChain(failure: Throwable): String = generateSequence(failure) { it.cause }
    .take(5)
    .joinToString(" ← ") { it.javaClass.simpleName }
