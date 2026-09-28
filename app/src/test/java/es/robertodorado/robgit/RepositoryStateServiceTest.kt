package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryStateServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun synchronizedWhenHeadsMatchAndTreeIsClean() {
        val fixture = fixture()

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED, 0, 0)
        assertFalse(state.changes.hasChanges)
    }

    @Test
    fun reportsNewWorkingTreeFile() {
        val fixture = fixture()
        File(fixture.local, "nuevo.txt").writeText("nuevo")

        val state = fixture.service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("nuevo.txt"), state.changes.newFiles)
        assertTrue(state.changes.modifiedFiles.isEmpty())
        assertTrue(state.changes.deletedFiles.isEmpty())
    }

    @Test
    fun reportsModifiedWorkingTreeFile() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("modificado")

        val state = fixture.service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("tracked.txt"), state.changes.modifiedFiles)
        assertTrue(state.changes.newFiles.isEmpty())
        assertTrue(state.changes.deletedFiles.isEmpty())
    }

    @Test
    fun reportsDeletedWorkingTreeFile() {
        val fixture = fixture()
        assertTrue(File(fixture.local, "tracked.txt").delete())

        val state = fixture.service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("tracked.txt"), state.changes.deletedFiles)
        assertTrue(state.changes.newFiles.isEmpty())
        assertTrue(state.changes.modifiedFiles.isEmpty())
    }

    @Test
    fun reportsOneLocalCommitAhead() {
        val fixture = fixture()
        commit(fixture.local, "local-1.txt", "local 1", "local 1")

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, 1, 0)
    }

    @Test
    fun reportsOneRemoteCommitAhead() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote-1.txt", "remote 1", "remote 1")

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, 0, 1)
    }

    @Test
    fun reportsSeveralLocalCommitsAhead() {
        val fixture = fixture()
        repeat(3) { index ->
            commit(fixture.local, "local-$index.txt", "local $index", "local $index")
        }

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, 3, 0)
    }

    @Test
    fun reportsSeveralRemoteCommitsAhead() {
        val fixture = fixture()
        repeat(3) { index ->
            commit(fixture.writer, "remote-$index.txt", "remote $index", "remote $index")
        }
        push(fixture.writer)

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, 0, 3)
    }

    @Test
    fun reportsRealDivergenceAndExclusiveCommitCounts() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "solo local", "solo local")
        commitAndPush(fixture.writer, "remote.txt", "solo remoto", "solo remoto")

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.DIVERGED, CommitRelation.DIVERGED, 1, 1)
    }

    @Test
    fun exposesStagedFilesSeparately() {
        val fixture = fixture()
        File(fixture.local, "staged.txt").writeText("staged")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }

        val state = fixture.service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("staged.txt"), state.changes.newFiles)
        assertEquals(setOf("staged.txt"), state.changes.stagedFiles)
    }

    @Test
    fun fetchFailureNeverReportsSynchronizedState() {
        val fixture = fixture()
        val missingRemote = File(temporaryFolder.root, "missing.git")
        Git.open(fixture.local).use { git ->
            git.repository.config.setString(
                "remote",
                "origin",
                "url",
                missingRemote.toURI().toString(),
            )
            git.repository.config.save()
        }

        val state = RepositoryStateService(missingRemote.toURI().toString())
            .refreshState(fixture.local)

        assertEquals(RepositoryStateType.ERROR, state.type)
        assertEquals(CommitRelation.UNDETERMINED, state.relation)
        assertFalse(state.remoteStateIsFresh)
        assertTrue(state.message.contains("no está verificado"))
    }

    @Test
    fun prepareClonesOnceAndThenOpensExistingRepository() {
        val fixture = fixture()
        val target = File(fixture.remote.parentFile, "persistent")
        val preparationService = RepositoryStateService(fixture.remote.toURI().toString())

        val first = preparationService.prepare(target)
        val second = preparationService.prepare(target)

        assertTrue(first.success)
        assertTrue(first.cloned)
        assertTrue(second.error ?: second.message, second.success)
        assertFalse(second.cloned)
        assertTrue(File(target, ".git").isDirectory)
    }

    @Test
    fun prepareNeverOverwritesAnExistingInvalidDirectory() {
        val target = temporaryFolder.newFolder("persistent-invalid")
        val marker = File(target, "trabajo-del-usuario.txt")
        marker.writeText("conservar")

        val result = RepositoryStateService("file:///unused.git").prepare(target)

        assertFalse(result.success)
        assertFalse(result.cloned)
        assertEquals("conservar", marker.readText())
        assertFalse(File(target, ".git").exists())
    }

    @Test
    fun downloadOneRemoteCommitByFastForward() {
        val fixture = fixture()
        val previousHead = head(fixture.local)
        commitAndPush(fixture.writer, "tracked.txt", "remoto 1", "remoto 1")
        val remoteHead = head(fixture.writer)
        val remoteCommitCount = commitCount(fixture.writer)

        val performance = PullPerformanceRecorder { 0 }
        val result = fixture.service.downloadFastForward(fixture.local, charArrayOf(), performance)
        val report = performance.snapshot()
        assertEquals(3, report.counters[PullCounter.STATUS_CALLS])
        assertEquals(4, report.counters[PullCounter.REV_WALK_CALLS])
        assertEquals(listOf(
            PullPhase.JGIT_CREDENTIALS, PullPhase.OPEN_REPOSITORY, PullPhase.VALIDATE_REPOSITORY,
            PullPhase.FETCH, PullPhase.INITIAL_REFS, PullPhase.INITIAL_GRAPH, PullPhase.INITIAL_STATUS,
            PullPhase.INITIAL_CHANGES, PullPhase.INITIAL_POLICY, PullPhase.BASELINE_REFS,
            PullPhase.GUARD_STATUS, PullPhase.GUARD_CHANGES, PullPhase.GUARD_HEAD,
            PullPhase.GUARD_REMOTE_REF, PullPhase.GUARD_VALIDATE, PullPhase.FAST_FORWARD,
            PullPhase.FINAL_REFS, PullPhase.FINAL_GRAPH, PullPhase.FINAL_STATUS,
            PullPhase.FINAL_CHANGES, PullPhase.FINAL_VALIDATE, PullPhase.ENGINE_CREDENTIAL_CLEANUP,
        ), report.timings.map { it.phase })

        assertEquals(result.error, DownloadOutcome.SUCCESS, result.outcome)
        assertEquals(previousHead, result.previousHead)
        assertEquals(remoteHead, result.newHead)
        assertEquals(1, result.commitsDownloaded)
        assertEquals("remoto 1", File(fixture.local, "tracked.txt").readText())
        assertRepositoryAt(fixture.local, remoteHead, remoteCommitCount)
        assertState(
            requireNotNull(result.finalState),
            RepositoryStateType.SYNCHRONIZED,
            CommitRelation.SYNCHRONIZED,
            0,
            0,
        )
    }

    @Test
    fun downloadSeveralRemoteCommitsByFastForward() {
        val fixture = fixture()
        repeat(3) { index ->
            commit(fixture.writer, "remote-$index.txt", "contenido $index", "remoto $index")
        }
        push(fixture.writer)
        val remoteHead = head(fixture.writer)
        val remoteCommitCount = commitCount(fixture.writer)

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(result.error, DownloadOutcome.SUCCESS, result.outcome)
        assertEquals(3, result.commitsDownloaded)
        repeat(3) { index ->
            assertEquals("contenido $index", File(fixture.local, "remote-$index.txt").readText())
        }
        assertRepositoryAt(fixture.local, remoteHead, remoteCommitCount)
    }

    @Test
    fun downloadWhenSynchronizedIsIdempotent() {
        val fixture = fixture()
        val previousHead = head(fixture.local)
        val previousContents = File(fixture.local, "tracked.txt").readText()
        val previousCommitCount = commitCount(fixture.local)

        val performance = PullPerformanceRecorder { 0 }
        val result = fixture.service.downloadFastForward(fixture.local, charArrayOf(), performance)
        val report = performance.snapshot()
        assertEquals(1, report.counters[PullCounter.STATUS_CALLS])
        assertEquals(0, report.counters[PullCounter.REV_WALK_CALLS] ?: 0)
        assertFalse(report.timings.any { it.phase == PullPhase.FAST_FORWARD || it.phase == PullPhase.GUARD_STATUS ||
            it.phase == PullPhase.FINAL_STATUS })

        assertEquals(DownloadOutcome.ALREADY_SYNCHRONIZED, result.outcome)
        assertEquals(0, result.commitsDownloaded)
        assertEquals(previousContents, File(fixture.local, "tracked.txt").readText())
        assertRepositoryAt(fixture.local, previousHead, previousCommitCount)
    }

    @Test
    fun downloadIsBlockedByModifiedLocalFile() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val remoteHead = head(fixture.writer)
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("cambio local sin commit")

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_CHANGES, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals(remoteHead, originMain(fixture.local))
        assertEquals("cambio local sin commit", File(fixture.local, "tracked.txt").readText())
        assertEquals(setOf("tracked.txt"), status(fixture.local).modified)
    }

    @Test
    fun downloadIsBlockedByUntrackedFileAndKeepsIt() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val localHead = head(fixture.local)
        val untracked = File(fixture.local, "sin-seguimiento.txt")
        untracked.writeText("conservar")

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_CHANGES, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertTrue(untracked.isFile)
        assertEquals("conservar", untracked.readText())
        assertEquals(setOf("sin-seguimiento.txt"), status(fixture.local).untracked)
    }

    @Test
    fun downloadIsBlockedByStagedChangeAndKeepsIndex() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val localHead = head(fixture.local)
        val staged = File(fixture.local, "staged.txt")
        staged.writeText("staged local")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_CHANGES, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals("staged local", staged.readText())
        assertEquals(setOf("staged.txt"), status(fixture.local).added)
    }

    @Test
    fun downloadIsBlockedWhenLocalHasCommits() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "solo local", "solo local")
        val localHead = head(fixture.local)
        val localCommitCount = commitCount(fixture.local)

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_COMMITS, result.outcome)
        assertRepositoryAt(fixture.local, localHead, localCommitCount, expectSynchronized = false)
        assertEquals("solo local", File(fixture.local, "local.txt").readText())
    }

    @Test
    fun downloadIsBlockedOnDivergenceWithoutMergeCommit() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "solo local", "solo local")
        val localHead = head(fixture.local)
        val localCommitCount = commitCount(fixture.local)
        commitAndPush(fixture.writer, "remote.txt", "solo remoto", "solo remoto")
        val remoteHead = head(fixture.writer)

        val performance = PullPerformanceRecorder { 0 }
        val result = fixture.service.downloadFastForward(fixture.local, charArrayOf(), performance)
        assertEquals(1, performance.snapshot().counters[PullCounter.STATUS_CALLS])
        assertEquals(4, performance.snapshot().counters[PullCounter.REV_WALK_CALLS])
        assertFalse(performance.snapshot().timings.any { it.phase == PullPhase.FAST_FORWARD })

        assertEquals(DownloadOutcome.DIVERGED, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals(remoteHead, originMain(fixture.local))
        assertEquals(localCommitCount, commitCount(fixture.local))
        assertEquals("solo local", File(fixture.local, "local.txt").readText())
        assertFalse(File(fixture.local, "remote.txt").exists())
        assertTrue(status(fixture.local).isClean)
    }

    @Test
    fun downloadFetchFailureDoesNotChangeHeadRefsOrFiles() {
        val fixture = fixture()
        val localHead = head(fixture.local)
        val trackedRemote = originMain(fixture.local)
        val contents = File(fixture.local, "tracked.txt").readText()
        val missingRemote = File(temporaryFolder.root, "download-missing.git")
        Git.open(fixture.local).use { git ->
            git.repository.config.setString(
                "remote",
                "origin",
                "url",
                missingRemote.toURI().toString(),
            )
            git.repository.config.save()
        }

        val performance = PullPerformanceRecorder { 0 }
        val result = RepositoryStateService(missingRemote.toURI().toString())
            .downloadFastForward(fixture.local, charArrayOf(), performance)
        assertTrue(performance.snapshot().timings.any { it.phase == PullPhase.FETCH })
        assertEquals(0, performance.snapshot().counters[PullCounter.STATUS_CALLS] ?: 0)
        assertFalse(performance.snapshot().timings.any { it.phase == PullPhase.FAST_FORWARD })

        assertEquals(DownloadOutcome.FETCH_ERROR, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals(trackedRemote, originMain(fixture.local))
        assertEquals(contents, File(fixture.local, "tracked.txt").readText())
        assertTrue(status(fixture.local).isClean)
    }

    @Test
    fun downloadRevalidatesStaleShownStateBeforeActing() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val remoteHead = head(fixture.writer)
        val shownState = fixture.service.refreshState(fixture.local)
        assertEquals(RepositoryStateType.REMOTE_AHEAD, shownState.type)
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("cambio posterior al estado mostrado")

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_CHANGES, result.outcome)
        assertEquals(localHead, head(fixture.local))
        assertEquals(remoteHead, originMain(fixture.local))
        assertEquals(
            "cambio posterior al estado mostrado",
            File(fixture.local, "tracked.txt").readText(),
        )
        assertEquals(setOf("tracked.txt"), status(fixture.local).modified)
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()

        val writer = File(root, "writer")
        Git.init().setInitialBranch("main").setDirectory(writer).call().use { git ->
            File(writer, "README.md").writeText("# Pruebas\n")
            File(writer, "tracked.txt").writeText("base")
            git.add().addFilepattern("README.md").addFilepattern("tracked.txt").call()
            git.commit()
                .setMessage("base")
                .setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost")
                .call()
            git.remoteAdd()
                .setName("origin")
                .setUri(URIish(remote.toURI().toString()))
                .call()
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }

        val local = File(root, "local")
        Git.cloneRepository()
            .setURI(remote.toURI().toString())
            .setBranch("main")
            .setDirectory(local)
            .call()
            .close()
        return Fixture(
            remote = remote,
            writer = writer,
            local = local,
            service = RepositoryStateService(remote.toURI().toString()),
        )
    }

    private fun commit(directory: File, path: String, contents: String, message: String) {
        File(directory, path).writeText(contents)
        Git.open(directory).use { git ->
            git.add().addFilepattern(path).call()
            git.commit()
                .setMessage(message)
                .setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost")
                .call()
        }
    }

    private fun commitAndPush(directory: File, path: String, contents: String, message: String) {
        commit(directory, path, contents, message)
        push(directory)
    }

    private fun push(directory: File) {
        Git.open(directory).use { git ->
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
    }

    private fun assertState(
        state: RepositoryStateSnapshot,
        type: RepositoryStateType,
        relation: CommitRelation,
        ahead: Int,
        behind: Int,
    ) {
        assertEquals(state.error, type, state.type)
        assertEquals(relation, state.relation)
        assertEquals(ahead, state.ahead)
        assertEquals(behind, state.behind)
        assertTrue(state.remoteStateIsFresh)
    }

    private fun assertRepositoryAt(
        directory: File,
        expectedHead: String,
        expectedCommitCount: Int,
        expectSynchronized: Boolean = true,
    ) {
        assertEquals(expectedHead, head(directory))
        if (expectSynchronized) {
            assertEquals(expectedHead, originMain(directory))
        }
        assertEquals(expectedCommitCount, commitCount(directory))
        assertTrue(status(directory).isClean)
    }

    private fun head(directory: File): String = Git.open(directory).use { git ->
        requireNotNull(git.repository.resolve("HEAD")).name
    }

    private fun originMain(directory: File): String = Git.open(directory).use { git ->
        requireNotNull(git.repository.resolve("refs/remotes/origin/main")).name
    }

    private fun commitCount(directory: File): Int = Git.open(directory).use { git ->
        git.log().call().count()
    }

    private fun status(directory: File) = Git.open(directory).use { git ->
        git.status().call()
    }

    private data class Fixture(
        val remote: File,
        val writer: File,
        val local: File,
        val service: RepositoryStateService,
    )
}
