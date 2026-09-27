package es.robertodorado.robgit

/** The only adapter from existing RobGit metadata to the AI domain. Performs no I/O. */
internal class AiContextBuilder(private val filter: AiContextFilter = AiContextFilter()) {
    fun build(
        repository: RepositoryConfig?,
        state: RepositoryStateSnapshot?,
        humanStatus: RepositoryHumanStatus,
        lastOperation: AiLastOperation? = null,
    ): AiContext {
        val changes = state?.changes ?: WorkingTreeChanges()
        // RobGit groups untracked and staged additions in newFiles.
        val additions = changes.newFiles intersect changes.stagedFiles
        val untracked = changes.newFiles - changes.stagedFiles
        return filter.filter(AiContext(
            repositoryName = repository?.displayName,
            branch = state?.branch ?: repository?.branch,
            humanStatus = AiHumanStatus(humanStatus.title, humanStatus.explanation,
                humanStatus.recommendation, humanStatus.blocked),
            gitStatus = state?.let {
                AiGitStatus(it.type.name, it.relation.name, it.ahead, it.behind,
                    it.relation == CommitRelation.DIVERGED, it.changes.conflictingFiles.isNotEmpty(),
                    it.remoteStateIsFresh, it.checkedAt.toString(), it.authenticationRequired,
                    it.authenticationRejected, it.repositoryAccessDenied)
            },
            changes = AiFileChanges(
                modified = changes.modifiedFiles.toList(),
                added = additions.toList(),
                deleted = changes.deletedFiles.toList(),
                untracked = untracked.toList(),
                staged = changes.stagedFiles.toList(),
                conflicting = changes.conflictingFiles.toList(),
            ),
            lastOperation = lastOperation,
            message = state?.message,
            error = state?.error,
        ))
    }
}
