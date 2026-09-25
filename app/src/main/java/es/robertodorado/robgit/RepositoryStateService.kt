package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
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
                val repository = git.repository
                check(!repository.isBare && repository.objectDatabase.exists()) {
                    "El repositorio persistente no es válido."
                }
                val origin = repository.config.getString("remote", "origin", "url")
                check(!origin.isNullOrBlank()) { "El repositorio no tiene origin configurado." }

                try {
                    git.fetch()
                        .setRemote("origin")
                        .setCredentialsProvider(null)
                        .setTimeout(60)
                        .call()
                } catch (failure: Throwable) {
                    if (failure !is Exception && failure !is LinkageError) throw failure
                    return errorSnapshot(
                        checkedAt,
                        "No se pudo actualizar el estado desde GitHub. El estado remoto no está verificado.",
                        failure,
                    )
                }

                val branch = repository.branch
                check(branch == "main") { "Se esperaba la rama main, se obtuvo $branch." }
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
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            return errorSnapshot(
                checkedAt,
                "No se ha podido determinar el estado del repositorio con seguridad.",
                failure,
            )
        }
    }

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
