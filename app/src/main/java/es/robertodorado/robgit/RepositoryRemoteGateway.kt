package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate

enum class PushTransportOutcome {
    ACCEPTED,
    REJECTED_REMOTE_CHANGED,
    AUTH_FAILED,
    AMBIGUOUS,
    ERROR,
}

data class PushTransportResult(
    val outcome: PushTransportOutcome,
    val detail: String? = null,
)

/** Narrow seam around network operations so safety races can be tested without Internet. */
interface RepositoryRemoteGateway {
    fun fetch(git: Git, credentials: CredentialsProvider?)

    fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult

    fun pushBranch(git: Git, credentials: CredentialsProvider, branch: String): PushTransportResult {
        require(branch == "main") { "El gateway no admite esta rama." }
        return pushMain(git, credentials)
    }
}

class JGitRepositoryRemoteGateway : RepositoryRemoteGateway {
    override fun fetch(git: Git, credentials: CredentialsProvider?) {
        git.fetch()
            .setRemote("origin")
            .setCredentialsProvider(credentials)
            .setTimeout(60)
            .call()
    }

    override fun pushMain(
        git: Git,
        credentials: CredentialsProvider,
    ): PushTransportResult = pushBranch(git, credentials, "main")

    override fun pushBranch(
        git: Git,
        credentials: CredentialsProvider,
        branch: String,
    ): PushTransportResult {
        val ref = "refs/heads/$branch"
        val results = git.push()
            .setRemote("origin")
            .setRefSpecs(RefSpec("$ref:$ref"))
            .setForce(false)
            .setCredentialsProvider(credentials)
            .setTimeout(60)
            .call()
            .toList()
        val updates = results.flatMap { it.remoteUpdates }
            .filter { it.remoteName == ref }
        if (updates.isEmpty()) {
            return PushTransportResult(PushTransportOutcome.AMBIGUOUS)
        }
        if (updates.any {
                it.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                    it.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
            }
        ) {
            return PushTransportResult(PushTransportOutcome.REJECTED_REMOTE_CHANGED)
        }
        if (updates.all {
                it.status == RemoteRefUpdate.Status.OK ||
                    it.status == RemoteRefUpdate.Status.UP_TO_DATE
            }
        ) {
            return PushTransportResult(PushTransportOutcome.ACCEPTED)
        }
        return PushTransportResult(
            PushTransportOutcome.ERROR,
            updates.joinToString { it.status.name },
        )
    }
}
