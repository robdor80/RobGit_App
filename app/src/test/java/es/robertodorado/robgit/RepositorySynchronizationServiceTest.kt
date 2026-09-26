package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositorySynchronizationServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun synchronizedCleanDoesNothing() {
        val fixture = fixture()
        val before = head(fixture.local)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.NOTHING_TO_DO, result.outcome)
        assertEquals(before, head(fixture.local))
        assertEquals(0, fixture.gateway.pushCount)
        assertTrue(status(fixture.local).isClean)
    }

    @Test fun remoteAheadOneDownloadsByFastForward() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "one.txt", "uno", "uno")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        assertSynchronized(fixture, result)
        assertEquals("uno", File(fixture.local, "one.txt").readText())
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun remoteAheadSeveralDownloadsByFastForward() {
        val fixture = fixture()
        repeat(3) { commit(fixture.writer, "remote-$it.txt", "$it", "remote $it") }
        push(fixture.writer)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        assertSynchronized(fixture, result)
        repeat(3) { assertEquals("$it", File(fixture.local, "remote-$it.txt").readText()) }
    }

    @Test fun localUncommittedChangesCommitAndPush() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("local")

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.SUCCESS_UPLOADED, result.outcome)
        assertTrue(requireNotNull(result.uploadResult).commitCreated)
        assertSynchronized(fixture, result)
        assertEquals("local", File(fixture.local, "tracked.txt").readText())
    }

    @Test fun localAheadCleanPushesWithoutExtraCommit() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "local", "local")
        val count = commitCount(fixture.local)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.SUCCESS_UPLOADED, result.outcome)
        assertFalse(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(count, commitCount(fixture.local))
        assertSynchronized(fixture, result)
    }

    @Test fun localAheadAndDirtyCreatesOneAdditionalCommitThenPushes() {
        val fixture = fixture()
        commit(fixture.local, "first.txt", "primero", "primero")
        val count = commitCount(fixture.local)
        File(fixture.local, "second.txt").writeText("segundo")

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.SUCCESS_UPLOADED, result.outcome)
        assertTrue(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(count + 1, commitCount(fixture.local))
        assertSynchronized(fixture, result)
    }

    @Test fun remoteAheadAndModifiedFileBlocksWithoutChangingIt() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("conservar")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES, result.outcome)
        assertEquals("conservar", File(fixture.local, "tracked.txt").readText())
        assertEquals(0, fixture.gateway.pushCount)
        assertTrue(status(fixture.local).modified.contains("tracked.txt"))
    }

    @Test fun remoteAheadAndUntrackedFileBlocksWithoutDeletingIt() {
        val fixture = remoteAheadFixture()
        val localFile = File(fixture.local, "nuevo.txt")
        localFile.writeText("conservar")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES, result.outcome)
        assertTrue(localFile.isFile)
        assertEquals("conservar", localFile.readText())
        assertTrue(status(fixture.local).untracked.contains("nuevo.txt"))
    }

    @Test fun remoteAheadAndStagedChangeBlocksAndKeepsIndex() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "staged.txt").writeText("conservar")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES, result.outcome)
        assertTrue(status(fixture.local).added.contains("staged.txt"))
        assertEquals("conservar", File(fixture.local, "staged.txt").readText())
    }

    @Test fun divergenceBlocksWithoutMergePushOrDownload() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "local", "local")
        val localHead = head(fixture.local)
        commitAndPush(fixture.writer, "remote.txt", "remote", "remote")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_DIVERGED, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertFalse(File(fixture.local, "remote.txt").exists())
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun existingConflictBlocksWithoutMutation() {
        val fixture = fixture()
        commit(fixture.local, "tracked.txt", "local", "local conflict")
        commitAndPush(fixture.writer, "tracked.txt", "remote", "remote conflict")
        Git.open(fixture.local).use { git ->
            JGitRepositoryRemoteGateway().fetch(git, null)
            git.merge().include(requireNotNull(git.repository.resolve("refs/remotes/origin/main"))).call()
        }
        val localHead = head(fixture.local)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_CONFLICTS, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertTrue(status(fixture.local).conflicting.contains("tracked.txt"))
        assertEquals(0, fixture.gateway.fetchCount)
    }

    @Test fun failedInitialFetchDoesNotMutateAnything() {
        val fixture = fixture()
        fixture.gateway.failFetchOn = 1
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("conservar")

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.FETCH_ERROR, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertTrue(status(fixture.local).modified.contains("tracked.txt"))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun staleSynchronizedStateDetectsRemoteAheadAndDownloads() {
        val fixture = fixture()
        assertEquals(RepositoryStateType.SYNCHRONIZED, fixture.service.refreshState(fixture.local).type)
        commitAndPush(fixture.writer, "remote.txt", "nuevo", "nuevo")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        assertSynchronized(fixture, result)
    }

    @Test fun staleRemoteAheadStateThatIsNowSynchronizedDoesNothing() {
        val fixture = remoteAheadFixture()
        assertEquals(RepositoryStateType.REMOTE_AHEAD, fixture.service.refreshState(fixture.local).type)
        assertEquals(DownloadOutcome.SUCCESS, fixture.service.downloadFastForward(fixture.local).outcome)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.NOTHING_TO_DO, result.outcome)
        assertSynchronized(fixture, result)
    }

    @Test fun staleLocalOnlyStateBlocksWhenRemoteChangesBeforeClick() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("local")
        assertEquals(RepositoryStateType.LOCAL_CHANGES, fixture.service.refreshState(fixture.local).type)
        commitAndPush(fixture.writer, "remote.txt", "remote", "remote")
        val localHead = head(fixture.local)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.BLOCKED_CHANGES_ON_BOTH_SIDES, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertTrue(status(fixture.local).modified.contains("tracked.txt"))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun downloadPathDoesNotRequirePat() {
        val fixture = remoteAheadFixture()
        val token = CharArray(0)

        val result = synchronize(fixture, token = token)

        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        assertSynchronized(fixture, result)
        assertTrue(token.isEmpty())
    }

    @Test fun uploadPathRequiresPatWhenChangesNeedCommit() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("local")

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.AUTH_REQUIRED, result.outcome)
        assertTrue(status(fixture.local).modified.contains("tracked.txt"))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun uploadPathRequiresPatForExistingLocalCommit() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "local", "local")
        val localHead = head(fixture.local)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.AUTH_REQUIRED, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun rejectedPushPreservesLocalCommit() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("local")
        fixture.gateway.pushBehavior = { git, credentials ->
            commitAndPush(fixture.writer, "race.txt", "remote", "race")
            fixture.gateway.delegate.pushMain(git, credentials)
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertTrue(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(requireNotNull(result.uploadResult).attemptedHead, head(fixture.local))
        assertNotEquals(head(fixture.local), bareHead(fixture.remote))
        assertEquals(1, fixture.gateway.pushCount)
    }

    @Test fun ambiguousPushUsesTheExistingSingleVerificationPolicy() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("local")
        fixture.gateway.pushBehavior = { git, credentials ->
            assertEquals(PushTransportOutcome.ACCEPTED, fixture.gateway.delegate.pushMain(git, credentials).outcome)
            PushTransportResult(PushTransportOutcome.AMBIGUOUS)
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.SUCCESS_UPLOADED, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertEquals(4, fixture.gateway.fetchCount)
        assertSynchronized(fixture, result)
    }

    private fun remoteAheadFixture(): Fixture = fixture().also {
        commitAndPush(it.writer, "remote.txt", "remote", "remote")
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val writer = File(root, "writer")
        Git.init().setInitialBranch("main").setDirectory(writer).call().use { git ->
            File(writer, "tracked.txt").writeText("base")
            git.add().addFilepattern("tracked.txt").call()
            git.commit().setMessage("base").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
            push(writer)
        }
        val local = File(root, "local")
        Git.cloneRepository().setURI(remote.toURI().toString()).setBranch("main")
            .setDirectory(local).call().close()
        val gateway = RecordingGateway()
        return Fixture(remote, writer, local, gateway, RepositoryStateService(remote.toURI().toString(), gateway))
    }

    private fun synchronize(
        fixture: Fixture,
        token: CharArray = "token-local".toCharArray(),
    ): SynchronizationResult = fixture.service.synchronizeSafely(
        fixture.local,
        token,
        "Cambios desde RobGit",
    )

    private fun assertSynchronized(fixture: Fixture, result: SynchronizationResult) {
        val state = requireNotNull(result.finalState)
        assertEquals(RepositoryStateType.SYNCHRONIZED, state.type)
        assertEquals(0, state.ahead)
        assertEquals(0, state.behind)
        assertEquals(head(fixture.local), bareHead(fixture.remote))
        assertTrue(status(fixture.local).isClean)
    }

    private fun commit(directory: File, path: String, contents: String, message: String) {
        File(directory, path).writeText(contents)
        Git.open(directory).use { git ->
            git.add().addFilepattern(path).call()
            git.commit().setMessage(message).setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
        }
    }

    private fun commitAndPush(directory: File, path: String, contents: String, message: String) {
        commit(directory, path, contents, message)
        push(directory)
    }

    private fun push(directory: File) {
        Git.open(directory).use { git ->
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    private fun head(directory: File): String = Git.open(directory).use {
        requireNotNull(it.repository.resolve(Constants.HEAD)).name
    }

    private fun bareHead(directory: File): String = org.eclipse.jgit.storage.file.FileRepositoryBuilder()
        .setGitDir(directory).setBare().build().use {
            requireNotNull(it.resolve("refs/heads/main")).name
        }

    private fun commitCount(directory: File): Int = Git.open(directory).use { it.log().call().count() }

    private fun status(directory: File) = Git.open(directory).use { it.status().call() }

    private data class Fixture(
        val remote: File,
        val writer: File,
        val local: File,
        val gateway: RecordingGateway,
        val service: RepositoryStateService,
    )

    private class RecordingGateway(
        val delegate: RepositoryRemoteGateway = JGitRepositoryRemoteGateway(),
    ) : RepositoryRemoteGateway {
        var fetchCount = 0
        var pushCount = 0
        var failFetchOn: Int? = null
        var pushBehavior: ((Git, CredentialsProvider) -> PushTransportResult)? = null

        override fun fetch(git: Git, credentials: CredentialsProvider?) {
            fetchCount++
            if (fetchCount == failFetchOn) throw IllegalStateException("fallo de red simulado")
            delegate.fetch(git, credentials)
        }

        override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult {
            pushCount++
            return pushBehavior?.invoke(git, credentials) ?: delegate.pushMain(git, credentials)
        }
    }
}
