package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

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

    @Test fun remoteAheadAndModifiedDisjointFileDownloadsThenUploads() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("conservar")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertEquals("conservar", File(fixture.local, "tracked.txt").readText())
        assertEquals("conservar", readBareFile(fixture.remote, "tracked.txt"))
        assertEquals("remote", readBareFile(fixture.remote, "remote.txt"))
    }

    @Test fun remoteAheadAndUntrackedDisjointFileDownloadsThenUploads() {
        val fixture = remoteAheadFixture()
        val localFile = File(fixture.local, "nuevo.txt")
        localFile.writeText("conservar")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertTrue(localFile.isFile)
        assertEquals("conservar", localFile.readText())
        assertEquals("conservar", readBareFile(fixture.remote, "nuevo.txt"))
    }

    @Test fun remoteAheadAndStagedDisjointChangePreservesIndexBeforeUpload() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "staged.txt").writeText("conservar")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)
        val originalIndexEntry = indexEntries(fixture.local, setOf("staged.txt"))
        var observedAfterDownload = false
        fixture.gateway.beforeFetch = { count ->
            if (count == 3) {
                observedAfterDownload = true
                assertEquals(remoteHead, head(fixture.local))
                assertEquals(originalIndexEntry, indexEntries(fixture.local, setOf("staged.txt")))
                assertTrue(status(fixture.local).added.contains("staged.txt"))
                assertEquals("conservar", File(fixture.local, "staged.txt").readText())
            }
        }

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertTrue(observedAfterDownload)
        assertEquals("conservar", File(fixture.local, "staged.txt").readText())
        assertEquals("conservar", readBareFile(fixture.remote, "staged.txt"))
    }

    @Test fun remoteAheadAndDeletedDisjointFileUploadsTheDeletion() {
        val fixture = remoteAheadFixture()
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)
        assertTrue(File(fixture.local, "tracked.txt").delete())

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertFalse(File(fixture.local, "tracked.txt").exists())
        assertEquals(null, readBareFile(fixture.remote, "tracked.txt"))
        assertEquals("remote", readBareFile(fixture.remote, "remote.txt"))
    }

    @Test fun disjointRemoteDeletionAndRenameAreAppliedBeforeUploadingLocalWork() {
        val fixture = fixture(mapOf("removed.txt" to "eliminar remoto", "before.txt" to "renombrar remoto"))
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        Git.open(fixture.writer).use { it.rm().addFilepattern("removed.txt").call() }
        renameAndCommit(fixture.writer, "before.txt", "after.txt")
        push(fixture.writer)
        val initialHead = head(fixture.local)
        val downloadedHead = bareHead(fixture.remote)

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, downloadedHead)
        for (path in listOf("removed.txt", "before.txt")) {
            assertFalse(File(fixture.local, path).exists())
            assertEquals(null, readBareFile(fixture.remote, path))
            assertTrue(indexEntries(fixture.local, setOf(path)).isEmpty())
        }
        assertEquals("renombrar remoto", File(fixture.local, "after.txt").readText())
        assertEquals("renombrar remoto", readBareFile(fixture.remote, "after.txt"))
        assertEquals("trabajo local", readBareFile(fixture.remote, "tracked.txt"))
    }

    @Test fun disjointRemoteFileDirectoryReplacementsWorkInBothDirections() {
        for (fileToDirectory in listOf(true, false)) {
            val oldPath = if (fileToDirectory) "shape" else "shape/old.txt"
            val newPath = if (fileToDirectory) "shape/new.txt" else "shape"
            val fixture = fixture(mapOf(oldPath to "versión anterior"))
            File(fixture.local, "tracked.txt").writeText("trabajo local")
            Git.open(fixture.writer).use { it.rm().addFilepattern(oldPath).call() }
            if (!fileToDirectory) {
                val emptyDirectory = File(fixture.writer, "shape")
                if (emptyDirectory.exists()) assertTrue(emptyDirectory.delete())
            }
            commitAndPush(fixture.writer, newPath, "reemplazo remoto", "reemplazo remoto")
            val initialHead = head(fixture.local)
            val downloadedHead = bareHead(fixture.remote)

            val result = synchronize(fixture)

            assertCombinedSuccess(fixture, result, initialHead, downloadedHead)
            assertEquals("trabajo local", readBareFile(fixture.remote, "tracked.txt"))
            assertEquals("reemplazo remoto", File(fixture.local, newPath).readText())
            assertEquals("reemplazo remoto", readBareFile(fixture.remote, newPath))
            assertEquals(null, readBareBytes(fixture.remote, oldPath))
            assertTrue(indexEntries(fixture.local, setOf(oldPath)).isEmpty())
            if (fileToDirectory) {
                assertTrue(File(fixture.local, "shape").isDirectory)
            } else {
                assertFalse(File(fixture.local, oldPath).exists())
                assertTrue(File(fixture.local, "shape").isFile)
            }
        }
    }

    @Test fun mixedDisjointChangesRemainByteExactThroughDownloadBeforeOneCommit() {
        val fixture = fixture(mapOf("deleted.txt" to "base eliminado", "staged.txt" to "base staged"))
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)
        File(fixture.local, "tracked.txt").writeBytes(byteArrayOf(0, 1, 2, 127, -1))
        File(fixture.local, "new.txt").writeText("nuevo sin stage\n")
        assertTrue(File(fixture.local, "deleted.txt").delete())
        File(fixture.local, "staged.txt").writeText("contenido staged\n")
        File(fixture.local, "staged-new.txt").writeText("nuevo staged\n")
        Git.open(fixture.local).use {
            it.add().addFilepattern("staged.txt").addFilepattern("staged-new.txt").call()
        }
        File(fixture.local, "staged.txt").writeText("contenido working tree distinto\n")
        val localPaths = setOf("tracked.txt", "new.txt", "deleted.txt", "staged.txt", "staged-new.txt")
        val originalFiles = fileContents(fixture.local, localPaths)
        val originalIndex = indexEntries(fixture.local, localPaths)
        val originalChanges = status(fixture.local)
        var observedAfterDownload = false
        fixture.gateway.beforeFetch = { count ->
            if (count == 3) {
                observedAfterDownload = true
                assertEquals(remoteHead, head(fixture.local))
                assertEquals(originalFiles, fileContents(fixture.local, localPaths))
                assertEquals(originalIndex, indexEntries(fixture.local, localPaths))
                val changes = status(fixture.local)
                assertEquals(originalChanges.untracked, changes.untracked)
                assertEquals(originalChanges.modified, changes.modified)
                assertEquals(originalChanges.missing, changes.missing)
                assertEquals(originalChanges.added, changes.added)
                assertEquals(originalChanges.changed, changes.changed)
                assertEquals(originalChanges.removed, changes.removed)
                assertEquals("remoto", File(fixture.local, "remote.txt").readText())
            }
        }

        val result = synchronize(fixture, message = "mensaje solicitado")

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertTrue(observedAfterDownload)
        assertEquals(originalFiles, fileContents(fixture.local, localPaths))
        assertEquals(null, readBareFile(fixture.remote, "deleted.txt"))
        assertEquals("nuevo sin stage\n", readBareFile(fixture.remote, "new.txt"))
        assertEquals("nuevo staged\n", readBareFile(fixture.remote, "staged-new.txt"))
        assertEquals("contenido working tree distinto\n", readBareFile(fixture.remote, "staged.txt"))
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), readBareBytes(fixture.remote, "tracked.txt"))
        Git.open(fixture.local).use { git ->
            assertEquals("mensaje solicitado", git.log().setMaxCount(1).call().single().fullMessage)
        }
        assertEquals(5, fixture.gateway.fetchCount)
    }

    @Test fun modifiedSamePathBlocksBeforeFastForwardStageCommitOrPush() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "tracked.txt", "remoto", "remoto")
        File(fixture.local, "tracked.txt").writeBytes(byteArrayOf(0, 1, -1, 42))
        val baseline = baseline(fixture)

        val result = synchronize(fixture, token = CharArray(0))

        assertOverlapWithoutMutation(fixture, result, baseline)
        assertEquals(1, fixture.gateway.fetchCount)
    }

    @Test fun untrackedSamePathAsRemoteAdditionBlocksWithoutMutation() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "same.txt", "remoto", "remoto")
        File(fixture.local, "same.txt").writeText("nuevo local")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun deletedSamePathAsRemoteModificationBlocksWithoutMutation() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "tracked.txt", "remoto", "remoto")
        assertTrue(File(fixture.local, "tracked.txt").delete())
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun stagedSamePathAsRemoteModificationBlocksAndPreservesBothVersions() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "tracked.txt", "remoto", "remoto")
        File(fixture.local, "tracked.txt").writeText("versión staged")
        Git.open(fixture.local).use { it.add().addFilepattern("tracked.txt").call() }
        File(fixture.local, "tracked.txt").writeText("versión working tree")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun stagedDeletionSamePathAsRemoteModificationBlocksWithoutMutation() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "tracked.txt", "remoto", "remoto")
        Git.open(fixture.local).use { it.rm().addFilepattern("tracked.txt").call() }
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteRenameBlocksWhenItsOldPathIsModifiedLocally() {
        val fixture = fixture()
        renameAndCommit(fixture.writer, "tracked.txt", "renamed.txt")
        push(fixture.writer)
        File(fixture.local, "tracked.txt").writeText("trabajo en ruta antigua")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteRenameBlocksWhenItsNewPathIsUntrackedLocally() {
        val fixture = fixture()
        renameAndCommit(fixture.writer, "tracked.txt", "renamed.txt")
        push(fixture.writer)
        File(fixture.local, "renamed.txt").writeText("trabajo en ruta nueva")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun localStagedRenameBlocksWhenRemoteChangesItsOldPath() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "tracked.txt", "remoto", "remoto")
        renameAndStage(fixture.local, "tracked.txt", "local-renamed.txt")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun localStagedRenameBlocksWhenRemoteAddsItsNewPath() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "local-renamed.txt", "remoto", "remoto")
        renameAndStage(fixture.local, "tracked.txt", "local-renamed.txt")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteFileAtParentOfLocalUntrackedPathBlocksWithoutMutation() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "foo", "archivo remoto", "remoto")
        writeFile(fixture.local, "foo/bar.txt", "archivo local")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteChildPathOfLocalUntrackedFileBlocksWithoutMutation() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "foo/bar.txt", "archivo remoto", "remoto")
        File(fixture.local, "foo").writeText("archivo local")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun similarlyPrefixedPathsAreDisjointInsteadOfDirectoryCollisions() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "foo/bar.txt", "remoto", "remoto")
        File(fixture.local, "foobar.txt").writeText("local")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertEquals("local", readBareFile(fixture.remote, "foobar.txt"))
        assertEquals("remoto", readBareFile(fixture.remote, "foo/bar.txt"))
    }

    @Test fun remoteAdditionCannotOverwriteAnExistingIgnoredFile() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "ignored.txt", "remoto", "remoto")
        ignoreLocally(fixture.local, "ignored.txt")
        File(fixture.local, "ignored.txt").writeText("ignorado que debe conservarse")
        File(fixture.local, "tracked.txt").writeText("cambio disjunto")
        assertTrue(status(fixture.local).ignoredNotInIndex.contains("ignored.txt"))
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteFileCannotOverwriteAnExistingIgnoredDirectoryAndItsChildren() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "ignored", "remoto", "remoto")
        ignoreLocally(fixture.local, "ignored/")
        writeFile(fixture.local, "ignored/private.txt", "ignorado que debe conservarse")
        File(fixture.local, "tracked.txt").writeText("cambio disjunto")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun remoteChildCannotOccupyAnExistingIgnoredFile() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "ignored/remote.txt", "remoto", "remoto")
        ignoreLocally(fixture.local, "ignored")
        File(fixture.local, "ignored").writeText("ignorado que debe conservarse")
        File(fixture.local, "tracked.txt").writeText("cambio disjunto")
        val baseline = baseline(fixture)

        assertOverlapWithoutMutation(fixture, synchronize(fixture), baseline)
    }

    @Test fun divergenceBlocksWithoutMergePushOrDownload() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "local", "local")
        commitAndPush(fixture.writer, "remote-only.txt", "remote", "remote")
        val baseline = baseline(fixture)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.BLOCKED_DIVERGED, result.outcome)
        assertNoMutation(fixture, baseline)
        assertFalse(File(fixture.local, "remote-only.txt").exists())
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
        val fixture = remoteAheadFixture()
        fixture.gateway.failFetchOn = 1
        File(fixture.local, "tracked.txt").writeText("conservar")
        val baseline = baseline(fixture)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.FETCH_ERROR, result.outcome)
        assertNoMutation(fixture, baseline)
    }

    @Test fun failedFetchImmediatelyBeforeFastForwardDoesNotMutateAnything() {
        val fixture = remoteAheadFixture()
        fixture.gateway.failFetchOn = 2
        File(fixture.local, "tracked.txt").writeText("conservar")
        val baseline = baseline(fixture)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.FETCH_ERROR, result.outcome)
        assertEquals(2, fixture.gateway.fetchCount)
        assertNoMutation(fixture, baseline)
        assertEquals(null, result.downloadResult)
    }

    @Test fun localContentChangedDuringRevalidationBlocksEvenWithTheSameStatusPaths() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("primera edición")
        var latestBaseline = baseline(fixture)
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                File(fixture.local, "tracked.txt").writeText("segunda edición del usuario")
                latestBaseline = baseline(fixture)
            }
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertNoMutation(fixture, latestBaseline)
        assertEquals(2, fixture.gateway.fetchCount)
        assertEquals(null, result.downloadResult)
    }

    @Test fun stagedContentChangedDuringRevalidationBlocksEvenWithTheSameWorktreeAndStatusPaths() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("primera versión staged")
        Git.open(fixture.local).use { it.add().addFilepattern("tracked.txt").call() }
        File(fixture.local, "tracked.txt").writeText("versión working tree estable")
        var latestBaseline = baseline(fixture)
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                File(fixture.local, "tracked.txt").writeText("segunda versión staged")
                Git.open(fixture.local).use { it.add().addFilepattern("tracked.txt").call() }
                File(fixture.local, "tracked.txt").writeText("versión working tree estable")
                latestBaseline = baseline(fixture)
            }
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertNoMutation(fixture, latestBaseline)
        assertEquals(2, fixture.gateway.fetchCount)
    }

    @Test fun hiddenWorkingEditAfterStagingBlocksEvenWhenStagedStatusAlreadyListsThePath() {
        val fixture = remoteAheadFixture()
        val tracked = File(fixture.local, "tracked.txt")
        tracked.writeText("step")
        val timestamp = Instant.parse("2000-01-01T00:00:00Z")
        Git.open(fixture.local).use { git ->
            git.add().addFilepattern("tracked.txt").call()
            val index = git.repository.lockDirCache()
            try {
                val entry = index.getEntry("tracked.txt")
                assertEquals("step", git.repository.open(entry.objectId).bytes.toString(Charsets.UTF_8))
                entry.setLastModified(timestamp)
                entry.setLength(4)
                index.write()
                assertTrue(index.commit())
            } finally {
                index.unlock()
            }
        }
        tracked.writeText("mine")
        Files.setLastModifiedTime(tracked.toPath(), FileTime.from(timestamp))
        val changes = status(fixture.local)
        assertTrue(changes.changed.contains("tracked.txt"))
        assertFalse("El fixture debe esconder solo la edición posterior al staging",
            changes.modified.contains("tracked.txt"))
        val baseline = baseline(fixture)

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertNoMutation(fixture, baseline)
        assertEquals(null, result.downloadResult)
        assertEquals(null, result.uploadResult)
    }

    @Test fun localHeadChangedDuringRevalidationBlocksAndKeepsTheConcurrentCommit() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo pendiente")
        var latestBaseline = baseline(fixture)
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                commit(fixture.local, "concurrent.txt", "commit local concurrente", "concurrente")
                latestBaseline = baseline(fixture)
            }
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertNoMutation(fixture, latestBaseline)
        assertEquals(2, fixture.gateway.fetchCount)
        assertEquals(null, result.downloadResult)
    }

    @Test fun remoteRaceBeforeFastForwardIsRecomputedAndOverlapBlocksWithoutMutation() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        var latestBaseline = baseline(fixture)
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                commitAndPush(fixture.writer, "tracked.txt", "remoto concurrente", "carrera remota")
                latestBaseline = baseline(fixture)
            }
        }

        val result = synchronize(fixture)

        assertOverlapWithoutMutation(fixture, result, latestBaseline)
        assertEquals(2, fixture.gateway.fetchCount)
        assertEquals("remoto concurrente", readBareFile(fixture.remote, "tracked.txt"))
    }

    @Test fun disjointRemoteRaceBeforeFastForwardRecomputesTheTipAndCompletes() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        val initialHead = head(fixture.local)
        var latestRemote = bareHead(fixture.remote)
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                commitAndPush(fixture.writer, "remote-race.txt", "remoto concurrente", "carrera remota")
                latestRemote = bareHead(fixture.remote)
            }
        }

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, latestRemote)
        assertEquals(2, requireNotNull(result.downloadResult).commitsDownloaded)
        assertEquals("remoto concurrente", File(fixture.local, "remote-race.txt").readText())
        assertEquals("trabajo local", readBareFile(fixture.remote, "tracked.txt"))
    }

    @Test fun localWorkRevertedAfterDownloadReportsOnlyDownloadWithoutAnExtraCommitOrPush() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("edición posteriormente revertida")
        val initialHead = head(fixture.local)
        val downloadedHead = bareHead(fixture.remote)
        val downloadedCommitCount = commitCount(fixture.writer)
        fixture.gateway.beforeFetch = { count ->
            if (count == 3) File(fixture.local, "tracked.txt").writeText("base")
        }

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED, result.outcome)
        val download = requireNotNull(result.downloadResult)
        assertEquals(DownloadOutcome.SUCCESS, download.outcome)
        assertEquals(initialHead, download.previousHead)
        assertEquals(downloadedHead, download.newHead)
        val upload = requireNotNull(result.uploadResult)
        assertEquals(UploadOutcome.NOTHING_TO_UPLOAD, upload.outcome)
        assertFalse(upload.commitCreated)
        assertEquals(downloadedHead, head(fixture.local))
        assertEquals(downloadedCommitCount, commitCount(fixture.local))
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(3, fixture.gateway.fetchCount)
        assertEquals("base", File(fixture.local, "tracked.txt").readText())
        assertNoMergeCommits(fixture.local)
        assertSynchronized(fixture, result)
    }

    @Test fun remoteRaceAtUploadInitialFetchReportsDownloadAndKeepsPendingLocalWork() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        val initialHead = head(fixture.local)
        val downloadedHead = bareHead(fixture.remote)
        val localIndex = indexEntries(fixture.local, setOf("tracked.txt"))
        fixture.gateway.beforeFetch = { count ->
            if (count == 3) commitAndPush(fixture.writer, "race.txt", "carrera", "carrera después del FF")
        }

        val result = synchronize(fixture)

        assertPartialDownload(fixture, result, initialHead, downloadedHead)
        assertEquals(SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertEquals(UploadOutcome.BLOCKED_REMOTE_AHEAD, requireNotNull(result.uploadResult).outcome)
        assertFalse(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(downloadedHead, head(fixture.local))
        assertEquals(localIndex, indexEntries(fixture.local, setOf("tracked.txt")))
        assertTrue(status(fixture.local).modified.contains("tracked.txt"))
        assertEquals("trabajo local", File(fixture.local, "tracked.txt").readText())
        assertEquals("base", readBareFile(fixture.remote, "tracked.txt"))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun remoteRaceAtUploadSecondFetchReportsDownloadAndKeepsTheLocalCommit() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        val initialHead = head(fixture.local)
        val downloadedHead = bareHead(fixture.remote)
        fixture.gateway.beforeFetch = { count ->
            if (count == 4) commitAndPush(fixture.writer, "race.txt", "carrera", "carrera antes del push")
        }

        val result = synchronize(fixture)

        assertPartialDownload(fixture, result, initialHead, downloadedHead)
        assertEquals(SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertEquals(UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED, requireNotNull(result.uploadResult).outcome)
        assertTrue(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(requireNotNull(result.uploadResult).attemptedHead, head(fixture.local))
        assertSingleParent(fixture.local, downloadedHead)
        assertTrue(status(fixture.local).isClean)
        assertEquals("trabajo local", File(fixture.local, "tracked.txt").readText())
        assertEquals("base", readBareFile(fixture.remote, "tracked.txt"))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun nonFastForwardPushAfterDownloadIsReallyRejectedAndNeverForced() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("trabajo local")
        val initialHead = head(fixture.local)
        val downloadedHead = bareHead(fixture.remote)
        fixture.gateway.pushBehavior = { git, credentials ->
            commitAndPush(fixture.writer, "race.txt", "carrera", "carrera durante push")
            fixture.gateway.delegate.pushMain(git, credentials).also {
                assertEquals(PushTransportOutcome.REJECTED_REMOTE_CHANGED, it.outcome)
            }
        }

        val result = synchronize(fixture)

        assertPartialDownload(fixture, result, initialHead, downloadedHead)
        assertEquals(SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertTrue(requireNotNull(result.uploadResult).commitCreated)
        assertEquals(requireNotNull(result.uploadResult).attemptedHead, head(fixture.local))
        assertSingleParent(fixture.local, downloadedHead)
        assertEquals("trabajo local", File(fixture.local, "tracked.txt").readText())
        assertEquals("base", readBareFile(fixture.remote, "tracked.txt"))
        assertEquals("carrera", readBareFile(fixture.remote, "race.txt"))
        assertEquals(1, fixture.gateway.pushCount)
    }

    @Test fun failedUploadFetchAfterDownloadReportsPartialProgressAndPreservesWork() {
        for (failedFetch in listOf(3, 4)) {
            val fixture = remoteAheadFixture()
            File(fixture.local, "tracked.txt").writeText("trabajo local")
            val initialHead = head(fixture.local)
            val downloadedHead = bareHead(fixture.remote)
            val localIndex = indexEntries(fixture.local, setOf("tracked.txt"))
            fixture.gateway.failFetchOn = failedFetch

            val result = synchronize(fixture)

            assertPartialDownload(fixture, result, initialHead, downloadedHead)
            assertEquals(SynchronizationOutcome.FETCH_ERROR, result.outcome)
            assertEquals(UploadOutcome.FETCH_ERROR, requireNotNull(result.uploadResult).outcome)
            assertEquals(failedFetch == 4, requireNotNull(result.uploadResult).commitCreated)
            assertEquals("trabajo local", File(fixture.local, "tracked.txt").readText())
            assertEquals(downloadedHead, bareHead(fixture.remote))
            assertEquals("base", readBareFile(fixture.remote, "tracked.txt"))
            assertEquals(0, fixture.gateway.pushCount)
            if (failedFetch == 3) {
                assertEquals(downloadedHead, head(fixture.local))
                assertEquals(localIndex, indexEntries(fixture.local, setOf("tracked.txt")))
                assertTrue(status(fixture.local).modified.contains("tracked.txt"))
            } else {
                assertEquals(requireNotNull(result.uploadResult).attemptedHead, head(fixture.local))
                assertSingleParent(fixture.local, downloadedHead)
                assertTrue(status(fixture.local).isClean)
                val finalState = requireNotNull(result.finalState)
                assertFalse(finalState.remoteStateIsFresh)
                assertEquals(CommitRelation.LOCAL_AHEAD, finalState.relation)
                assertFalse(finalState.changes.hasChanges)
            }
        }
    }

    @Test fun disjointChangesRequireAuthenticationBeforeDownloadingAnything() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("local")
        val baseline = baseline(fixture)

        val result = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.AUTH_REQUIRED, result.outcome)
        assertNoMutation(fixture, baseline)
        assertEquals(null, result.downloadResult)
    }

    @Test fun disjointChangesRejectEmptyCommitMessageBeforeDownloadingAnything() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("local")
        val baseline = baseline(fixture)

        val result = synchronize(fixture, message = "  \n ")

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertNoMutation(fixture, baseline)
        assertEquals(null, result.downloadResult)
    }

    @Test fun repeatedSynchronizationAfterDownloadAndUploadIsIdempotent() {
        val fixture = remoteAheadFixture()
        File(fixture.local, "tracked.txt").writeText("local")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)
        assertCombinedSuccess(fixture, synchronize(fixture), initialHead, remoteHead)
        val baseline = baseline(fixture)
        val pushes = fixture.gateway.pushCount

        val repeated = synchronize(fixture, token = CharArray(0))

        assertEquals(SynchronizationOutcome.NOTHING_TO_DO, repeated.outcome)
        assertSynchronized(fixture, repeated)
        assertLocalUnchanged(fixture, baseline)
        assertEquals(baseline.remoteHead, bareHead(fixture.remote))
        assertEquals(pushes, fixture.gateway.pushCount)
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

    @Test fun staleLocalOnlyStateRechecksDisjointRemoteChangesBeforeClick() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("local")
        assertEquals(RepositoryStateType.LOCAL_CHANGES, fixture.service.refreshState(fixture.local).type)
        commitAndPush(fixture.writer, "remote.txt", "remote", "remote")
        val initialHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)

        val result = synchronize(fixture)

        assertCombinedSuccess(fixture, result, initialHead, remoteHead)
        assertEquals("local", readBareFile(fixture.remote, "tracked.txt"))
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

    private fun fixture(extraFiles: Map<String, String> = emptyMap()): Fixture {
        val root = temporaryFolder.newFolder()
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val writer = File(root, "writer")
        Git.init().setInitialBranch("main").setDirectory(writer).call().use { git ->
            (mapOf("tracked.txt" to "base", "remote.txt" to "remote base") + extraFiles)
                .forEach { (path, contents) -> writeFile(writer, path, contents) }
            git.add().addFilepattern(".").call()
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
        message: String = "Cambios desde RobGit",
    ): SynchronizationResult = fixture.service.synchronizeSafely(
        fixture.local,
        token,
        message,
    )

    private fun assertCombinedSuccess(
        fixture: Fixture,
        result: SynchronizationResult,
        initialHead: String,
        downloadedHead: String,
    ) {
        assertEquals(result.toString(), SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED, result.outcome)
        val download = requireNotNull(result.downloadResult)
        assertEquals(DownloadOutcome.SUCCESS, download.outcome)
        assertEquals(initialHead, download.previousHead)
        assertEquals(downloadedHead, download.newHead)
        assertTrue(download.commitsDownloaded > 0)
        val upload = requireNotNull(result.uploadResult)
        assertEquals(UploadOutcome.SUCCESS, upload.outcome)
        assertTrue(upload.commitCreated)
        assertEquals(downloadedHead, upload.previousHead)
        assertEquals(head(fixture.local), upload.attemptedHead)
        assertEquals(1, fixture.gateway.pushCount)
        assertSingleParent(fixture.local, downloadedHead)
        assertNoMergeCommits(fixture.local)
        assertSynchronized(fixture, result)
    }

    private fun assertPartialDownload(
        fixture: Fixture,
        result: SynchronizationResult,
        initialHead: String,
        downloadedHead: String,
    ) {
        val download = requireNotNull(result.downloadResult)
        assertEquals(DownloadOutcome.SUCCESS, download.outcome)
        assertEquals(initialHead, download.previousHead)
        assertEquals(downloadedHead, download.newHead)
        val message = result.message.lowercase()
        assertTrue(result.message, message.contains("descarg"))
        assertTrue(result.message, message.contains("local"))
        assertTrue(result.message, message.contains("conserv") || message.contains("guardad") ||
            message.contains("intacto"))
        assertFalse(result.message, message.contains("no ha realizado ningún cambio"))
        result.finalState?.let { assertEquals(head(fixture.local), it.localHead) }
        assertEquals(RepositoryState.SAFE, Git.open(fixture.local).use { it.repository.repositoryState })
        assertNoMergeCommits(fixture.local)
    }

    private fun assertOverlapWithoutMutation(
        fixture: Fixture,
        result: SynchronizationResult,
        baseline: Baseline,
    ) {
        assertEquals(result.toString(), SynchronizationOutcome.BLOCKED_OVERLAPPING_FILES, result.outcome)
        assertEquals(null, result.downloadResult)
        assertEquals(null, result.uploadResult)
        assertNoMutation(fixture, baseline)
    }

    private fun baseline(fixture: Fixture) = Baseline(
        head = head(fixture.local),
        remoteHead = bareHead(fixture.remote),
        commitCount = commitCount(fixture.local),
        files = workingTreeFiles(fixture.local),
        index = File(fixture.local, ".git/index").readBytes().toList(),
    )

    private fun assertNoMutation(fixture: Fixture, baseline: Baseline) {
        assertLocalUnchanged(fixture, baseline)
        assertEquals(baseline.remoteHead, bareHead(fixture.remote))
        assertEquals(0, fixture.gateway.pushCount)
    }

    private fun assertLocalUnchanged(fixture: Fixture, baseline: Baseline) {
        assertEquals(baseline.head, head(fixture.local))
        assertEquals(baseline.commitCount, commitCount(fixture.local))
        assertEquals(baseline.files, workingTreeFiles(fixture.local))
        assertArrayEquals(baseline.index.toByteArray(), File(fixture.local, ".git/index").readBytes())
        assertEquals(RepositoryState.SAFE, Git.open(fixture.local).use { it.repository.repositoryState })
    }

    private fun workingTreeFiles(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .onEnter { it.name != ".git" }
        .filter { it.isFile }
        .associate { file -> file.relativeTo(directory).invariantSeparatorsPath to file.readBytes().toList() }

    private fun fileContents(directory: File, paths: Set<String>): Map<String, List<Byte>?> =
        paths.associateWith { path -> File(directory, path).takeIf { it.isFile }?.readBytes()?.toList() }

    private fun indexEntries(directory: File, paths: Set<String>): List<IndexEntry> = Git.open(directory).use { git ->
        val index = git.repository.readDirCache()
        (0 until index.entryCount).map { index.getEntry(it) }
            .filter { it.pathString in paths }
            .map {
                IndexEntry(it.pathString, it.stage, it.objectId.name, it.rawMode,
                    it.isAssumeValid, it.isSkipWorkTree, it.isIntentToAdd)
            }
    }

    private fun assertSingleParent(directory: File, expectedParent: String) {
        Git.open(directory).use { git ->
            val commit = git.log().setMaxCount(1).call().single()
            assertEquals(1, commit.parentCount)
            assertEquals(expectedParent, commit.getParent(0).name)
        }
    }

    private fun assertNoMergeCommits(directory: File) {
        Git.open(directory).use { git ->
            assertTrue(git.log().call().all { it.parentCount <= 1 })
        }
    }

    private fun assertSynchronized(fixture: Fixture, result: SynchronizationResult) {
        val state = requireNotNull(result.finalState)
        assertEquals(RepositoryStateType.SYNCHRONIZED, state.type)
        assertEquals(0, state.ahead)
        assertEquals(0, state.behind)
        assertEquals(head(fixture.local), bareHead(fixture.remote))
        assertTrue(status(fixture.local).isClean)
    }

    private fun commit(directory: File, path: String, contents: String, message: String) {
        writeFile(directory, path, contents)
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

    private fun writeFile(directory: File, path: String, contents: String) {
        val file = File(directory, path)
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(contents)
    }

    private fun ignoreLocally(directory: File, pattern: String) {
        val exclude = File(directory, ".git/info/exclude")
        requireNotNull(exclude.parentFile).mkdirs()
        exclude.appendText("\n$pattern\n")
    }

    private fun renameAndStage(directory: File, oldPath: String, newPath: String) {
        val destination = File(directory, newPath)
        requireNotNull(destination.parentFile).mkdirs()
        assertTrue(File(directory, oldPath).renameTo(destination))
        Git.open(directory).use { git ->
            git.rm().addFilepattern(oldPath).call()
            git.add().addFilepattern(newPath).call()
        }
    }

    private fun renameAndCommit(directory: File, oldPath: String, newPath: String) {
        renameAndStage(directory, oldPath, newPath)
        Git.open(directory).use { git ->
            git.commit().setMessage("rename remoto").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
        }
    }

    private fun push(directory: File) {
        Git.open(directory).use { git ->
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    private fun head(directory: File): String = Git.open(directory).use {
        requireNotNull(it.repository.resolve(Constants.HEAD)).name
    }

    private fun bareHead(directory: File): String = FileRepositoryBuilder()
        .setGitDir(directory).setBare().build().use {
            requireNotNull(it.resolve("refs/heads/main")).name
        }

    private fun readBareFile(directory: File, path: String): String? =
        readBareBytes(directory, path)?.toString(Charsets.UTF_8)

    private fun readBareBytes(directory: File, path: String): ByteArray? = FileRepositoryBuilder()
        .setGitDir(directory).setBare().build().use { repository ->
            RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(requireNotNull(repository.resolve("refs/heads/main")))
                TreeWalk.forPath(repository, path, commit.tree)?.use { entry ->
                    if (entry.getFileMode(0).objectType == Constants.OBJ_BLOB) {
                        repository.open(entry.getObjectId(0)).bytes
                    } else {
                        null
                    }
                }
            }
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

    private data class Baseline(
        val head: String,
        val remoteHead: String,
        val commitCount: Int,
        val files: Map<String, List<Byte>>,
        val index: List<Byte>,
    )

    private data class IndexEntry(
        val path: String,
        val stage: Int,
        val objectId: String,
        val mode: Int,
        val assumeValid: Boolean,
        val skipWorkTree: Boolean,
        val intentToAdd: Boolean,
    )

    private class RecordingGateway(
        val delegate: RepositoryRemoteGateway = JGitRepositoryRemoteGateway(),
    ) : RepositoryRemoteGateway {
        var fetchCount = 0
        var pushCount = 0
        var failFetchOn: Int? = null
        var beforeFetch: ((Int) -> Unit)? = null
        var pushBehavior: ((Git, CredentialsProvider) -> PushTransportResult)? = null

        override fun fetch(git: Git, credentials: CredentialsProvider?) {
            fetchCount++
            beforeFetch?.invoke(fetchCount)
            if (fetchCount == failFetchOn) throw IllegalStateException("fallo de red simulado")
            delegate.fetch(git, credentials)
        }

        override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult {
            pushCount++
            return pushBehavior?.invoke(git, credentials) ?: delegate.pushMain(git, credentials)
        }
    }
}
