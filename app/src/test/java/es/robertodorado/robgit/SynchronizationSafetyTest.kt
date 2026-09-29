package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

class SynchronizationSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun changesToGitRulesBlockBeforeDownloadingOrStagingLocalWork() {
        for (path in listOf(".gitignore", ".gitattributes", ".gitmodules")) {
            val fixture = fixture()
            File(fixture.local, "local.txt").writeText("trabajo que conservar")
            commitAndPush(fixture.writer, path, "# reglas remotas nuevas\n")
            val initialHead = head(fixture.local)
            val remoteHead = head(fixture.writer)
            val indexBytes = File(fixture.local, ".git/index").readBytes()

            val result = synchronize(fixture)

            assertEquals(path, SynchronizationOutcome.ERROR, result.outcome)
            assertEquals(initialHead, head(fixture.local))
            assertEquals(remoteHead, bareHead(fixture.remote))
            assertEquals("trabajo que conservar", File(fixture.local, "local.txt").readText())
            assertFalse(File(fixture.local, path).exists())
            assertTrue(indexBytes.contentEquals(File(fixture.local, ".git/index").readBytes()))
            assertEquals(0, fixture.gateway.pushCount)
        }
    }

    @Test fun assumeUnchangedIndexBlocksInsteadOfTrustingIncompleteStatus() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("contenido oculto")
        Git.open(fixture.local).use { git ->
            val cache = git.repository.lockDirCache()
            try {
                cache.getEntry("tracked.txt").isAssumeValid = true
                cache.write()
                assertTrue(cache.commit())
            } finally {
                cache.unlock()
            }
        }
        File(fixture.local, "local.txt").writeText("otro cambio local")
        commitAndPush(fixture.writer, "remote.txt", "contenido remoto")
        val initialHead = head(fixture.local)
        val indexBytes = File(fixture.local, ".git/index").readBytes()

        val result = synchronize(fixture)

        assertEquals(SynchronizationOutcome.ERROR, result.outcome)
        assertEquals(initialHead, head(fixture.local))
        assertEquals("contenido oculto", File(fixture.local, "tracked.txt").readText())
        assertEquals("otro cambio local", File(fixture.local, "local.txt").readText())
        assertFalse(File(fixture.local, "remote.txt").exists())
        assertTrue(indexBytes.contentEquals(File(fixture.local, ".git/index").readBytes()))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun hiddenSameSizeAndTimestampChangeCannotBeOverwrittenByRemoteAdvance() {
        val fixture = fixture()
        val tracked = File(fixture.local, "tracked.txt")
        hideTrackedModificationFromStatCache(fixture)
        File(fixture.local, "local.txt").writeText("otro cambio local")
        Git.open(fixture.local).use { git ->
            assertFalse("El fixture debe esconder el cambio del status basado en stat",
                git.status().call().modified.contains("tracked.txt"))
        }
        commitAndPush(fixture.writer, "tracked.txt", "remote")
        val initialHead = head(fixture.local)
        val remoteHead = head(fixture.writer)
        val indexBytes = File(fixture.local, ".git/index").readBytes()

        val result = synchronize(fixture)

        assertTrue(result.message, result.outcome in setOf(
            SynchronizationOutcome.ERROR, SynchronizationOutcome.BLOCKED_OVERLAPPING_FILES))
        assertEquals(initialHead, head(fixture.local))
        assertEquals(remoteHead, bareHead(fixture.remote))
        assertEquals("mine", tracked.readText())
        assertEquals("otro cambio local", File(fixture.local, "local.txt").readText())
        assertTrue(indexBytes.contentEquals(File(fixture.local, ".git/index").readBytes()))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun hiddenLocalChangeBlocksEvenWhenRemoteIsDisjointInsteadOfReportingFalseUploadSuccess() {
        val fixture = fixture()
        hideTrackedModificationFromStatCache(fixture)
        File(fixture.local, "local.txt").writeText("otro cambio local")
        commitAndPush(fixture.writer, "remote.txt", "remoto disjunto")
        val initialHead = head(fixture.local)
        val remoteHead = head(fixture.writer)
        val indexBytes = File(fixture.local, ".git/index").readBytes()

        val result = synchronize(fixture)

        assertEquals(result.message, SynchronizationOutcome.ERROR, result.outcome)
        assertEquals(initialHead, head(fixture.local))
        assertEquals(remoteHead, bareHead(fixture.remote))
        assertEquals("mine", File(fixture.local, "tracked.txt").readText())
        assertEquals("otro cambio local", File(fixture.local, "local.txt").readText())
        assertFalse(File(fixture.local, "remote.txt").exists())
        assertTrue(indexBytes.contentEquals(File(fixture.local, ".git/index").readBytes()))
        assertEquals(0, fixture.gateway.pushCount)
    }

    @Test fun checkoutPreflightLeavesWorkingTreeAndIndexBytesUntouched() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("versión preparada")
        Git.open(fixture.local).use { it.add().addFilepattern("tracked.txt").call() }
        File(fixture.local, "tracked.txt").writeText("versión posterior sin preparar")
        commitAndPush(fixture.writer, "remote.txt", "remoto")
        val state = fixture.service.refreshState(fixture.local)
        val indexBytes = File(fixture.local, ".git/index").readBytes()

        Git.open(fixture.local).use { git ->
            val local = ObjectId.fromString(state.localHead)
            val remote = ObjectId.fromString(state.remoteHead)
            val work = SynchronizationSafety.captureLocalWork(git, state.changes)
            val paths = SynchronizationSafety.affectedRemotePaths(git.repository, local, remote)
            assertFalse(SynchronizationSafety.hasCollision(work, paths))

            SynchronizationSafety.verifyCheckoutIsSafe(git.repository, local, remote, paths)

            assertEquals(work, SynchronizationSafety.captureLocalWork(git, state.changes))
            assertEquals(local, git.repository.resolve(Constants.HEAD))
        }
        assertTrue(indexBytes.contentEquals(File(fixture.local, ".git/index").readBytes()))
        assertEquals("versión posterior sin preparar", File(fixture.local, "tracked.txt").readText())
        assertFalse(File(fixture.local, "remote.txt").exists())
    }

    @Test fun contentGuardDetectsChangesEvenWhenFileSizeAndTimestampStayEqual() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("local")
        val tracked = File(fixture.local, "tracked.txt")
        val timestamp = Instant.parse("2000-01-01T00:00:00Z")
        Git.open(fixture.local).use { git ->
            val cache = git.repository.lockDirCache()
            try {
                cache.getEntry("tracked.txt").setLastModified(timestamp)
                cache.getEntry("tracked.txt").setLength(4)
                cache.write()
                assertTrue(cache.commit())
            } finally {
                cache.unlock()
            }
        }
        Files.setLastModifiedTime(tracked.toPath(), FileTime.from(timestamp))
        val state = fixture.service.refreshState(fixture.local)
        Git.open(fixture.local).use { git ->
            val baseline = SynchronizationSafety.captureLocalWork(git, state.changes)

            tracked.writeText("mine")
            Files.setLastModifiedTime(tracked.toPath(), FileTime.from(timestamp))
            assertFalse(git.status().call().modified.contains("tracked.txt"))

            assertThrows(IllegalStateException::class.java) {
                SynchronizationSafety.captureLocalWork(git, baseline.changes)
            }
        }
    }

    @Test fun remoteLineEndingConversionIsVerifiedUsingGitContentSemantics() {
        val fixture = fixture()
        Git.open(fixture.local).use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", true)
            git.repository.config.save()
        }
        File(fixture.local, "local.txt").writeText("local\n")
        commitAndPush(fixture.writer, "remote.txt", "remoto\nsegunda línea\n")

        val result = synchronize(fixture)

        assertEquals(result.message, SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED, result.outcome)
        assertEquals("remoto\r\nsegunda línea\r\n", File(fixture.local, "remote.txt").readText())
        assertEquals(head(fixture.local), bareHead(fixture.remote))
        Git.open(fixture.local).use { assertTrue(it.status().call().isClean) }
    }

    @Test fun existingAttributesAreUsedToVerifyContentWithoutTrustingCachedStat() {
        val fixture = fixture(attributes = "*.txt text eol=crlf\n")
        Git.open(fixture.local).use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", false)
            git.repository.config.save()
        }
        File(fixture.local, "local.txt").writeText("local\n")
        commitAndPush(fixture.writer, "remote.txt", "remoto\nsegunda línea\n")

        val result = synchronize(fixture)

        assertEquals(result.message, SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED, result.outcome)
        assertEquals("remoto\r\nsegunda línea\r\n", File(fixture.local, "remote.txt").readText())
        assertEquals(head(fixture.local), bareHead(fixture.remote))
        Git.open(fixture.local).use { assertTrue(it.status().call().isClean) }
    }

    private fun fixture(attributes: String? = null): Fixture {
        val root = temporaryFolder.newFolder()
        val remote = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val writer = File(root, "writer")
        Git.init().setInitialBranch("main").setDirectory(writer).call().use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", false)
            git.repository.config.save()
            File(writer, "tracked.txt").writeText("base")
            git.add().addFilepattern("tracked.txt").call()
            if (attributes != null) {
                File(writer, ".gitattributes").writeText(attributes)
                git.add().addFilepattern(".gitattributes").call()
            }
            git.commit().setMessage("base").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
            push(writer)
        }
        val local = File(root, "local")
        Git.cloneRepository().setURI(remote.toURI().toString()).setBranch("main")
            .setDirectory(local).call().close()
        val gateway = RecordingGateway()
        return Fixture(remote, writer, local, gateway,
            RepositoryStateService(remote.toURI().toString(), gateway))
    }

    private fun synchronize(fixture: Fixture) = fixture.service.synchronizeSafely(
        fixture.local, "token-local".toCharArray(), "Trabajo local")

    private fun hideTrackedModificationFromStatCache(fixture: Fixture) {
        val timestamp = Instant.parse("2000-01-01T00:00:00Z")
        Git.open(fixture.local).use { git ->
            val cache = git.repository.lockDirCache()
            try {
                val entry = cache.getEntry("tracked.txt")
                entry.setLastModified(timestamp)
                entry.setLength(4)
                cache.write()
                assertTrue(cache.commit())
            } finally {
                cache.unlock()
            }
        }
        val tracked = File(fixture.local, "tracked.txt")
        tracked.writeText("mine")
        Files.setLastModifiedTime(tracked.toPath(), FileTime.from(timestamp))
    }

    private fun commitAndPush(directory: File, path: String, contents: String) {
        File(directory, path).writeText(contents)
        Git.open(directory).use { git ->
            git.add().addFilepattern(path).call()
            git.commit().setMessage("cambio remoto").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
        }
        push(directory)
    }

    private fun push(directory: File) {
        Git.open(directory).use { git ->
            git.push().setRemote("origin").setForce(false)
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
    }

    private fun head(directory: File): String = Git.open(directory).use {
        requireNotNull(it.repository.resolve(Constants.HEAD)).name
    }

    private fun bareHead(directory: File): String = org.eclipse.jgit.storage.file.FileRepositoryBuilder()
        .setGitDir(directory).setBare().build().use {
            requireNotNull(it.resolve("refs/heads/main")).name
        }

    private data class Fixture(val remote: File, val writer: File, val local: File,
                               val gateway: RecordingGateway, val service: RepositoryStateService)

    private class RecordingGateway : RepositoryRemoteGateway {
        private val delegate = JGitRepositoryRemoteGateway()
        var pushCount = 0

        override fun fetch(git: Git, credentials: CredentialsProvider?) = delegate.fetch(git, credentials)

        override fun pushMain(git: Git, credentials: CredentialsProvider): PushTransportResult {
            pushCount++
            return delegate.pushMain(git, credentials)
        }
    }
}
