package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class DirectedStagingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun stagesOnlySelectedLeavesAndPreservesUnrelatedIndexAndWorkingVersions() {
        repository().use { git ->
            val root = git.repository.workTree
            File(root, "selected.txt").writeText("selected")
            File(root, "unrelated.txt").writeText("staged unrelated")
            git.add().addFilepattern("unrelated.txt").call()
            val unrelatedId = git.repository.readDirCache().getEntry("unrelated.txt").objectId
            File(root, "unrelated.txt").writeText("unstaged unrelated")
            File(root, "unrelated-new.txt").writeText("untracked unrelated")
            val recorder = SyncPerformanceRecorder()

            SyncPerformance.withRecorder(recorder) {
                DirectedStaging.stage(git, WorkingTreeChanges(modifiedFiles = setOf("selected.txt")))
            }

            val index = git.repository.readDirCache()
            assertEquals("selected", git.repository.open(index.getEntry("selected.txt").objectId)
                .bytes.toString(Charsets.UTF_8))
            assertEquals(unrelatedId, index.getEntry("unrelated.txt").objectId)
            assertNull(index.getEntry("unrelated-new.txt"))
            assertEquals("unstaged unrelated", File(root, "unrelated.txt").readText())
            assertEquals(1L, recorder.snapshot().counters[SyncCounter.STAGING_PATHS])
            assertEquals(1L, recorder.snapshot().counters[SyncCounter.STAGING_STREAMS])
            assertEquals(0L, recorder.snapshot().counters[SyncCounter.GLOBAL_ADD_CALLS])
        }
    }

    @Test fun directedDeleteDoesNotStageAnotherDeletionOrUntrackedFile() {
        repository().use { git ->
            val root = git.repository.workTree
            assertTrue(File(root, "selected.txt").delete())
            assertTrue(File(root, "unrelated.txt").delete())
            File(root, "new.txt").writeText("untracked")
            val unrelatedId = git.repository.readDirCache().getEntry("unrelated.txt").objectId
            val recorder = SyncPerformanceRecorder()

            SyncPerformance.withRecorder(recorder) {
                DirectedStaging.stage(git, WorkingTreeChanges(deletedFiles = setOf("selected.txt")))
            }

            val index = git.repository.readDirCache()
            assertNull(index.getEntry("selected.txt"))
            assertEquals(unrelatedId, index.getEntry("unrelated.txt").objectId)
            assertNull(index.getEntry("new.txt"))
            assertEquals(1L, recorder.snapshot().counters[SyncCounter.ADD_CALLS])
            assertEquals(1L, recorder.snapshot().counters[SyncCounter.STAGING_DELETE_PATHS])
            assertFalse(recorder.snapshot().timings.any { it.phase == SyncPhase.ADD })
        }
    }

    @Test fun stagedDeletionRecreatedAndStagedAdditionMissingFollowLatestWorkingTreePolicy() {
        repository().use { git ->
            val root = git.repository.workTree
            git.rm().addFilepattern("selected.txt").call()
            File(root, "selected.txt").writeText("recreated")
            File(root, "added-missing.txt").writeText("staged")
            git.add().addFilepattern("added-missing.txt").call()
            assertTrue(File(root, "added-missing.txt").delete())

            DirectedStaging.stage(git, WorkingTreeChanges(
                newFiles = setOf("selected.txt", "added-missing.txt"),
                deletedFiles = setOf("selected.txt", "added-missing.txt"),
                stagedFiles = setOf("selected.txt", "added-missing.txt"),
            ))

            val index = git.repository.readDirCache()
            assertEquals("recreated", git.repository.open(index.getEntry("selected.txt").objectId)
                .bytes.toString(Charsets.UTF_8))
            assertNull(index.getEntry("added-missing.txt"))
        }
    }

    @Test fun replacedSelectedFileDoesNotStageUnselectedChildrenThroughPrefixFilter() {
        repository().use { git ->
            val root = git.repository.workTree
            assertTrue(File(root, "selected.txt").delete())
            assertTrue(File(root, "selected.txt").mkdir())
            File(root, "selected.txt/unrelated-child.txt").writeText("concurrent untracked")

            DirectedStaging.stage(git, WorkingTreeChanges(modifiedFiles = setOf("selected.txt")))

            val index = git.repository.readDirCache()
            assertNull(index.getEntry("selected.txt/unrelated-child.txt"))
            assertNull(index.getEntry("selected.txt"))
            assertEquals("concurrent untracked", File(root, "selected.txt/unrelated-child.txt").readText())
        }
    }

    @Test fun unrelatedTimestampMismatchesDoNotOpenStagingStreams() {
        repository().use { git ->
            val root = git.repository.workTree
            repeat(320) { File(root, "clean-$it.bin").writeText("clean $it") }
            git.add().addFilepattern(".").call()
            val cache = git.repository.lockDirCache()
            try {
                repeat(320) { cache.getEntry("clean-$it.bin").setLastModified(Instant.EPOCH) }
                cache.write()
                assertTrue(cache.commit())
            } finally { cache.unlock() }
            File(root, "selected.txt").writeText("selected")
            val recorder = SyncPerformanceRecorder()

            SyncPerformance.withRecorder(recorder) {
                DirectedStaging.stage(git, WorkingTreeChanges(modifiedFiles = setOf("selected.txt")))
            }

            assertEquals(1L, recorder.snapshot().counters[SyncCounter.STAGING_STREAMS])
            assertEquals(8L, recorder.snapshot().counters[SyncCounter.STAGING_STREAM_BYTES])
            assertEquals(0L, recorder.snapshot().counters[SyncCounter.GLOBAL_ADD_CALLS])
        }
    }

    @Test fun selectedFileDirectoryReplacementsMatchPreviousGlobalStagingSemantics() {
        for (fileToDirectory in listOf(true, false)) {
            val snapshots = listOf(false, true).map { directed ->
                repository().use { git ->
                    val root = git.repository.workTree
                    if (!fileToDirectory) {
                        assertTrue(File(root, "selected.txt").delete())
                        assertTrue(File(root, "selected.txt").mkdir())
                        File(root, "selected.txt/child.txt").writeText("base")
                        git.add().addFilepattern(".").call()
                        git.commit().setMessage("directory base").setAuthor("Test", "test@localhost")
                            .setCommitter("Test", "test@localhost").call()
                    }
                    val source = if (fileToDirectory) "selected.txt" else "selected.txt/child.txt"
                    val target = if (fileToDirectory) "selected.txt/child.txt" else "selected.txt"
                    assertTrue(File(root, source).delete())
                    if (fileToDirectory) assertTrue(File(root, "selected.txt").mkdir())
                    else assertTrue(File(root, "selected.txt").delete())
                    File(root, target).writeText("new local")
                    if (directed) DirectedStaging.stage(git, WorkingTreeChanges(
                        newFiles = setOf(target), deletedFiles = setOf(source)))
                    else {
                        git.add().addFilepattern(".").call()
                        git.add().setUpdate(true).addFilepattern(".").call()
                    }
                    val index = git.repository.readDirCache()
                    assertNull(index.getEntry(source))
                    assertEquals("new local", git.repository.open(index.getEntry(target).objectId)
                        .bytes.toString(Charsets.UTF_8))
                    (0 until index.entryCount).map {
                        val entry = index.getEntry(it)
                        Triple(entry.pathString, entry.objectId.name, entry.rawMode)
                    }
                }
            }
            assertEquals(snapshots[0], snapshots[1])
        }
    }

    @Test fun autocrlfTypeReplacementKeepsExistingJGitFailureWithoutMutatingIndexOrContent() {
        // JGit 7.8 hasCrLfInIndex dereferences a null entry for this virtual tree.
        // Prove this is also the old policy's limitation; do not bypass EOL safeguards.
        for (directed in listOf(false, true)) repository().use { git ->
            val root = git.repository.workTree
            assertTrue(File(root, "selected.txt").delete())
            assertTrue(File(root, "selected.txt").mkdir())
            File(root, "selected.txt/child.txt").writeText("base")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("directory base").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.repository.config.setBoolean("core", null, "autocrlf", true)
            git.repository.config.save()
            assertTrue(File(root, "selected.txt/child.txt").delete())
            assertTrue(File(root, "selected.txt").delete())
            File(root, "selected.txt").writeText("new local")
            val before = File(root, ".git/index").readBytes()

            assertThrows(NullPointerException::class.java) {
                if (directed) DirectedStaging.stage(git, WorkingTreeChanges(
                    newFiles = setOf("selected.txt"), deletedFiles = setOf("selected.txt/child.txt")))
                else git.add().addFilepattern(".").call()
            }

            assertArrayEquals(before, File(root, ".git/index").readBytes())
            assertEquals("new local", File(root, "selected.txt").readText())
        }
    }

    private fun repository(): Git {
        val root = temporaryFolder.newFolder()
        return Git.init().setDirectory(root).setInitialBranch("main").call().also { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", false)
            git.repository.config.save()
            File(root, "selected.txt").writeText("base")
            File(root, "unrelated.txt").writeText("base")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("base").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
        }
    }
}
