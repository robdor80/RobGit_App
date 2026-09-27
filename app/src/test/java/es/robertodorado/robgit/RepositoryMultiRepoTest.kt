package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.CredentialsProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryMultiRepoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun twoRepositoriesAndBranchesAreAnalyzedIndependently() {
        val main = fixture("one", "main")
        val develop = fixture("two", "develop")
        assertEquals("main", main.service.refreshState(main.local).branch)
        assertEquals("develop", develop.service.refreshState(develop.local).branch)
        assertEquals(RepositoryStateType.SYNCHRONIZED, main.service.refreshState(main.local).type)
        assertEquals(RepositoryStateType.SYNCHRONIZED, develop.service.refreshState(develop.local).type)

        commitAndPush(develop, "remote.txt", "new")
        assertEquals(RepositoryStateType.REMOTE_AHEAD, develop.service.refreshState(develop.local).type)
        assertEquals(RepositoryStateType.SYNCHRONIZED, main.service.refreshState(main.local).type)
    }

    @Test fun pullOnOneRepositoryLeavesTheOtherUntouched() {
        val a = fixture("a", "main")
        val b = fixture("b", "develop")
        val beforeB = head(b.local)
        commitAndPush(a, "remote.txt", "from remote")
        assertEquals(DownloadOutcome.SUCCESS, a.service.downloadFastForward(a.local).outcome)
        assertEquals(beforeB, head(b.local))
        assertFalse(File(b.local, "remote.txt").exists())
    }

    @Test fun pushOnOneRepositoryLeavesTheOtherUntouched() {
        val a = fixture("a", "main")
        val b = fixture("b", "develop")
        val beforeB = head(b.local)
        File(a.local, "local.txt").writeText("from a")
        val result = a.service.uploadSafely(a.local, "local-test-token".toCharArray(), "A change")
        assertEquals(result.error, UploadOutcome.SUCCESS, result.outcome)
        assertEquals(beforeB, head(b.local))
        assertFalse(File(b.local, "local.txt").exists())
    }

    @Test fun pushUsesTheConfiguredNonMainBranch() {
        val develop = fixture("develop", "develop")
        File(develop.local, "local.txt").writeText("from develop")
        val result = develop.service.uploadSafely(
            develop.local, "local-test-token".toCharArray(), "Develop change",
        )
        assertEquals(result.error, UploadOutcome.SUCCESS, result.outcome)
        Git.open(develop.local).use { git ->
            assertEquals("develop", git.repository.branch)
            assertEquals(git.repository.resolve("HEAD"), git.repository.resolve("refs/remotes/origin/develop"))
        }
    }

    @Test fun synchronizationOnOneRepositoryLeavesTheOtherUntouched() {
        val a = fixture("a", "main")
        val b = fixture("b", "develop")
        val beforeB = head(b.local)
        commitAndPush(a, "remote.txt", "from remote")
        val result = a.service.synchronizeSafely(a.local, charArrayOf(), "unused")
        assertEquals(result.error, SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        assertEquals(beforeB, head(b.local))
    }

    @Test fun expectedRemoteIsVerifiedBeforeAnyMutation() {
        val a = fixture("a", "main")
        val b = fixture("b", "main")
        val before = head(b.local)
        assertEquals(RepositoryStateType.ERROR, a.service.refreshState(b.local).type)
        assertEquals(DownloadOutcome.ERROR, a.service.downloadFastForward(b.local).outcome)
        assertEquals(before, head(b.local))
    }

    @Test fun invalidDirectoryIsPreservedAndExistingValidRepositoryIsReused() {
        val a = fixture("a", "main")
        val reopened = a.service.prepare(a.local)
        assertTrue(reopened.success)
        assertFalse(reopened.cloned)
        val invalid = File(folder.root, "invalid").apply { mkdirs() }
        val marker = File(invalid, "keep.txt").apply { writeText("safe") }
        assertFalse(a.service.prepare(invalid).success)
        assertEquals("safe", marker.readText())
    }

    @Test fun missingConfiguredBranchDoesNotOccupyTheFinalDirectory() {
        val a = fixture("a", "main")
        val missing = RepositoryStateService(repositoryUrl = a.remote.toURI().toString(), branch = "missing")
        val target = File(folder.root, "missing-target")
        val result = missing.prepare(target)
        assertFalse(result.success)
        assertTrue("${result.message}: ${result.error}", result.message.contains("rama configurada"))
        assertFalse(target.exists())
    }

    @Test fun privateRepositoryFetchRequestsAuthorizationAndClearsToken() {
        val repo = fixture("private", "main")
        val service = RepositoryStateService(repo.remote.toURI().toString(), unauthorizedGateway())
        val token = "temporary-token".toCharArray()
        val state = service.refreshState(repo.local, token)
        assertEquals(RepositoryStateType.ERROR, state.type)
        assertTrue(state.authenticationRequired)
        assertTrue(token.all { it == '\u0000' })

        val download = service.downloadFastForward(repo.local)
        assertEquals(DownloadOutcome.FETCH_ERROR, download.outcome)
        assertTrue(download.finalState?.authenticationRequired == true)

        val sync = service.synchronizeSafely(repo.local, charArrayOf(), "unused")
        assertEquals(SynchronizationOutcome.AUTH_REQUIRED, sync.outcome)
    }

    @Test fun fetchFailureNeverReturnsCredentialText() {
        val repo = fixture("redacted-fetch", "main")
        val secret = "oauth-sensitive-test-value"
        val gateway = object : RepositoryRemoteGateway {
            override fun fetch(git: Git, credentials: CredentialsProvider?) {
                throw IllegalStateException("401 $secret")
            }
            override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult =
                error("Unexpected push")
        }
        val token = secret.toCharArray()
        val state = RepositoryStateService(repo.remote.toURI().toString(), gateway).refreshState(repo.local, token)
        assertTrue(state.authenticationRejected)
        assertFalse(state.toString().contains(secret))
        assertTrue(token.all { it == '\u0000' })
    }

    @Test fun repositoryNotFoundDoesNotMarkOAuthTokenAsInvalid() {
        val repo = fixture("repository-not-found", "main")
        val gateway = object : RepositoryRemoteGateway {
            override fun fetch(git: Git, credentials: CredentialsProvider?) {
                throw IllegalStateException("404 repository not found")
            }
            override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult =
                error("Unexpected push")
        }
        val state = RepositoryStateService(repo.remote.toURI().toString(), gateway)
            .refreshState(repo.local, "oauth-test-value".toCharArray())
        assertTrue(state.authenticationRequired)
        assertFalse(state.authenticationRejected)
    }

    @Test fun authorizationOnSecondSynchronizationFetchIsPreserved() {
        val repo = fixture("second-fetch", "main")
        commitAndPush(repo, "remote.txt", "ahead")
        var fetches = 0
        val gateway = object : RepositoryRemoteGateway {
            override fun fetch(git: Git, credentials: CredentialsProvider?) {
                fetches++
                if (fetches == 2) throw IllegalStateException("not authorized")
                JGitRepositoryRemoteGateway().fetch(git, credentials)
            }
            override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult =
                error("Unexpected push")
        }
        val service = RepositoryStateService(repo.remote.toURI().toString(), gateway)
        val result = service.synchronizeSafely(repo.local, charArrayOf(), "unused")
        assertEquals(SynchronizationOutcome.AUTH_REQUIRED, result.outcome)
        assertEquals(2, fetches)
        assertTrue(result.finalState?.authenticationRequired == true)
    }

    private fun unauthorizedGateway(): RepositoryRemoteGateway = object : RepositoryRemoteGateway {
        override fun fetch(git: Git, credentials: CredentialsProvider?) {
            throw IllegalStateException("not authorized")
        }
        override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult =
            error("Unexpected push")
    }

    private fun fixture(name: String, branch: String): Fixture {
        val root = folder.newFolder(name)
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch(branch).setDirectory(remote).call().close()
        val writer = File(root, "writer")
        Git.init().setInitialBranch(branch).setDirectory(writer).call().use { git ->
            File(writer, "README.md").writeText("# $name")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
            git.push().setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch")).call()
        }
        val local = File(root, "local")
        val service = RepositoryStateService(repositoryUrl = remote.toURI().toString(), branch = branch)
        assertTrue(service.prepare(local).success)
        return Fixture(remote, writer, local, branch, service)
    }

    private fun commitAndPush(fixture: Fixture, path: String, content: String) {
        File(fixture.writer, path).writeText(content)
        Git.open(fixture.writer).use { git ->
            git.add().addFilepattern(path).call()
            git.commit().setMessage(content).setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.push().setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/${fixture.branch}:refs/heads/${fixture.branch}")).call()
        }
    }

    private fun head(directory: File): String = Git.open(directory).use { git ->
        requireNotNull(git.repository.resolve("HEAD")).name
    }

    private data class Fixture(
        val remote: File,
        val writer: File,
        val local: File,
        val branch: String,
        val service: RepositoryStateService,
    )
}
