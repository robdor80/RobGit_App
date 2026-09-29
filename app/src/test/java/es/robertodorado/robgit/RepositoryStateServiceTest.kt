package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryStateServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun localRefreshReportsModifiedNewDeletedAndStagedWithoutFetch() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        File(fixture.local, "tracked.txt").writeText("external edit")
        assertTrue(File(fixture.local, "README.md").delete())
        File(fixture.local, "new.txt").writeText("new")
        File(fixture.local, "staged.txt").writeText("staged")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }
        val gateway = CountingGateway()
        val state = RepositoryStateService(fixture.remote.toURI().toString(), gateway)
            .refreshLocalState(fixture.local, previous)
        assertEquals(setOf("tracked.txt"), state.changes.modifiedFiles)
        assertEquals(setOf("README.md"), state.changes.deletedFiles)
        assertEquals(setOf("new.txt", "staged.txt"), state.changes.newFiles)
        assertEquals(setOf("staged.txt"), state.changes.stagedFiles)
        assertEquals(0, gateway.fetches)
        assertFalse(state.remoteStateIsFresh)
        assertEquals(previous.remoteCheckedAt, state.remoteCheckedAt)
        assertTrue(state.localStateIsFresh)
        assertTrue(RepositoryStatusPresenter.present(state).pushEnabled)
    }

    @Test fun localRefreshReportsAtomicRenameAsNewAndDeletedFiles() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        assertTrue(File(fixture.local, "tracked.txt").renameTo(File(fixture.local, "renamed.txt")))
        val state = fixture.service.refreshLocalState(fixture.local, previous)
        assertEquals(setOf("tracked.txt"), state.changes.deletedFiles)
        assertEquals(setOf("renamed.txt"), state.changes.newFiles)
        assertFalse(state.remoteStateIsFresh)
    }

    @Test fun localRefreshDoesNotPretendToKnowNewRemoteCommits() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        commitAndPush(fixture.writer, "remote.txt", "remote", "remote advance")
        val gateway = CountingGateway()
        val service = RepositoryStateService(fixture.remote.toURI().toString(), gateway)
        val local = service.refreshLocalState(fixture.local, previous)
        assertEquals(previous.remoteHead, local.remoteHead)
        assertEquals(previous.remoteCheckedAt, local.remoteCheckedAt)
        assertFalse(local.remoteStateIsFresh)
        assertFalse(RepositoryStatusPresenter.present(local).title.contains("al día"))
        assertEquals(0, gateway.fetches)
        val full = service.refreshState(fixture.local)
        assertEquals(1, gateway.fetches)
        assertTrue(full.remoteStateIsFresh)
        assertEquals(CommitRelation.REMOTE_AHEAD, full.relation)
    }

    @Test fun repeatedLocalRefreshesKeepOriginalRemoteTime() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        val first = fixture.service.refreshLocalState(fixture.local, previous)
        val second = fixture.service.refreshLocalState(fixture.local, first)
        assertEquals(previous.remoteCheckedAt, second.remoteCheckedAt)
        assertFalse(second.remoteStateIsFresh)
    }

    @Test fun changedTrackingRefOrBranchDoesNotReuseRemoteVerification() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        for (incompatible in listOf(previous.copy(remoteHead = "other"), previous.copy(branch = "other"))) {
            val local = fixture.service.refreshLocalState(fixture.local, incompatible)
            assertNull(local.remoteCheckedAt)
            assertFalse(RepositoryStatusPresenter.present(local).pushEnabled)
        }
    }

    @Test fun unknownOrFailedRemoteKnowledgeCannotEnablePush() {
        val fixture = fixture()
        File(fixture.local, "new.txt").writeText("new")
        val failed = fixture.service.refreshState(fixture.local).copy(type = RepositoryStateType.ERROR)
        for (previous in listOf(null, failed)) {
            val local = fixture.service.refreshLocalState(fixture.local, previous)
            assertNull(local.remoteCheckedAt)
            assertFalse(RepositoryStatusPresenter.present(local).pushEnabled)
            assertEquals("Tienes trabajo local pendiente.", RepositoryStatusPresenter.present(local).title)
        }
    }

    @Test fun externalMergeConflictsAreReadAndRemainBlocked() {
        val fixture = fixture()
        val previous = fixture.service.refreshState(fixture.local)
        Git.open(fixture.local).use { it.checkout().setCreateBranch(true).setName("side").call() }
        commit(fixture.local, "tracked.txt", "side contents", "side edit")
        val side = head(fixture.local)
        Git.open(fixture.local).use { it.checkout().setName("main").call() }
        commit(fixture.local, "tracked.txt", "main contents", "main edit")
        Git.open(fixture.local).use { it.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(side)).call() }
        val local = fixture.service.refreshLocalState(fixture.local, previous)
        assertEquals(setOf("tracked.txt"), local.changes.conflictingFiles)
        assertTrue(local.localStateIsFresh)
        assertFalse(local.localRepositoryIsSafe)
        assertTrue(RepositoryStatusPresenter.present(local).blocked)
    }

    @Test fun localRefreshIsPureReadOfWorkingTreeIndexAndRefs() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("external contents")
        fun bytes(): Map<String, String> = java.nio.file.Files.walk(fixture.local.toPath()).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) }.toList().associate {
                fixture.local.toPath().relativize(it).toString() to java.nio.file.Files.readAllBytes(it).joinToString()
            }
        }
        val before = bytes()
        val state = fixture.service.refreshLocalState(fixture.local, null)
        assertTrue(state.localStateIsFresh)
        assertEquals(before, bytes())
    }

    @Test fun localRefreshNeverInvokesHeavySynchronizationPhases() {
        val fixture = fixture()
        File(fixture.local, "new.txt").writeText("new")
        val recorder = SyncPerformanceRecorder { 0 }
        SyncPerformance.withRecorder(recorder) { fixture.service.refreshLocalState(fixture.local, null) }
        val report = recorder.snapshot()
        assertEquals(1L, report.counters[SyncCounter.STATUS_CALLS])
        for (counter in listOf(SyncCounter.FETCH_CALLS, SyncCounter.CAPTURE_CALLS,
            SyncCounter.FINGERPRINTS, SyncCounter.SHA256_FILES, SyncCounter.WORKING_OBJECTS,
            SyncCounter.ADD_CALLS, SyncCounter.COMMIT_CALLS, SyncCounter.PUSH_CALLS)) {
            assertEquals(counter.name, 0L, report.counters[counter] ?: 0L)
        }
        assertFalse(report.timings.any { it.phase in setOf(SyncPhase.CAPTURE, SyncPhase.FINGERPRINT,
            SyncPhase.SHA_READ, SyncPhase.ADD, SyncPhase.FETCH) })
    }

    @Test fun missingWorkspaceReturnsSafeLocalError() {
        val fixture = fixture()
        val local = fixture.service.refreshLocalState(File(fixture.local, "missing"), null)
        assertEquals(RepositoryStateType.ERROR, local.type)
        assertFalse(local.localStateIsFresh)
        assertFalse(local.remoteStateIsFresh)
        assertTrue(RepositoryStatusPresenter.present(local).blocked)
    }

    private class CountingGateway : RepositoryRemoteGateway {
        var fetches = 0
        override fun fetch(git: Git, credentials: org.eclipse.jgit.transport.CredentialsProvider?) {
            fetches++
            JGitRepositoryRemoteGateway().fetch(git, credentials)
        }
        override fun pushMain(git: Git, credentials: org.eclipse.jgit.transport.CredentialsProvider): PushTransportResult =
            error("Local refresh must never push")
    }

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
    fun remoteAheadSnapshotKeepsAllPendingWorkingTreeAndIndexCategories() {
        val fixture = fixture()
        val initialHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("modificado local")
        assertTrue(File(fixture.local, "README.md").delete())
        File(fixture.local, "nuevo.txt").writeText("nuevo local")
        File(fixture.local, "staged.txt").writeText("preparado local")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }
        commitAndPush(fixture.writer, "remote.txt", "remoto", "avance remoto")

        val state = fixture.service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.LOCAL_CHANGES, CommitRelation.REMOTE_AHEAD, 0, 1)
        assertEquals(initialHead, state.localHead)
        assertEquals(setOf("nuevo.txt", "staged.txt"), state.changes.newFiles)
        assertEquals(setOf("tracked.txt"), state.changes.modifiedFiles)
        assertEquals(setOf("README.md"), state.changes.deletedFiles)
        assertEquals(setOf("staged.txt"), state.changes.stagedFiles)
        assertTrue(state.changes.conflictingFiles.isEmpty())
        assertFalse(File(fixture.local, "remote.txt").exists())
    }

    @Test
    fun directPullStillBlocksRemoteAheadWithMixedDisjointLocalWork() {
        val fixture = fixture()
        val initialHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("modificado local")
        assertTrue(File(fixture.local, "README.md").delete())
        File(fixture.local, "nuevo.txt").writeText("nuevo local")
        File(fixture.local, "staged.txt").writeText("preparado local")
        val stagedBlob = Git.open(fixture.local).use { git ->
            git.add().addFilepattern("staged.txt").call()
            git.repository.readDirCache().getEntry("staged.txt").objectId.name
        }
        commitAndPush(fixture.writer, "remote.txt", "remoto", "avance remoto")

        val result = fixture.service.downloadFastForward(fixture.local)

        assertEquals(DownloadOutcome.BLOCKED_LOCAL_CHANGES, result.outcome)
        assertEquals(initialHead, head(fixture.local))
        assertEquals("modificado local", File(fixture.local, "tracked.txt").readText())
        assertEquals("nuevo local", File(fixture.local, "nuevo.txt").readText())
        assertEquals("preparado local", File(fixture.local, "staged.txt").readText())
        assertFalse(File(fixture.local, "README.md").exists())
        assertFalse(File(fixture.local, "remote.txt").exists())
        Git.open(fixture.local).use { git ->
            assertEquals(stagedBlob, git.repository.readDirCache().getEntry("staged.txt").objectId.name)
        }
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
