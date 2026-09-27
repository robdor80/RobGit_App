package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RepositoryMigrationManagerTest {
    @get:Rule val folder = TemporaryFolder()

    private val privateRoot get() = File(folder.root, "files/repos")
    private val documents get() = File(folder.root, "Documents")
    private val journalRoot get() = File(folder.root, "files/migrations")
    private val registryFile get() = File(folder.root, "files/repositories.properties")
    private fun registry() = RepositoryRegistry(registryFile, privateRoot)
    private fun resolver(access: Boolean = true) = RepositoryWorkspaceResolver(privateRoot, documents) { access }
    private val offlineGateway = object : RepositoryRemoteGateway {
        override fun fetch(git: Git, credentials: CredentialsProvider?) = Unit
        override fun pushMain(git: Git, credentials: CredentialsProvider) = PushTransportResult(PushTransportOutcome.ERROR)
    }
    private fun manager(store: RepositoryRegistry = registry(), access: Boolean = true,
        gateway: RepositoryRemoteGateway = offlineGateway) =
        RepositoryMigrationManager(store, resolver(access), journalRoot, gateway)

    private fun fixture(): RepositoryConfig {
        val config = registry().load().selected!!
        val source = resolver().resolve(config)
        Git.init().setDirectory(source).setInitialBranch("main").call().use { git ->
            File(source, "README.md").writeText("tracked original\n")
            File(source, "tracked-delete.txt").writeText("tracked before deletion\n")
            git.add().addFilepattern("README.md").call()
            git.add().addFilepattern("tracked-delete.txt").call()
            val first = git.commit().setMessage("initial").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            val settings = git.repository.config
            settings.setString("remote", "origin", "url", config.remoteUrl)
            settings.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            settings.setString("branch", "main", "remote", "origin")
            settings.setString("branch", "main", "merge", "refs/heads/main")
            settings.save()
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(first.id); update() }
            File(source, "local-commit.txt").writeText("not pushed\n")
            git.add().addFilepattern("local-commit.txt").call()
            git.commit().setMessage("local ahead").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            File(source, "staged.txt").writeText("staged local work\n")
            git.add().addFilepattern("staged.txt").call()
        }
        File(source, "README.md").writeText("tracked modified without commit\n")
        File(source, "tracked-delete.txt").delete()
        File(source, "untracked.txt").writeText("untracked local work\n")
        File(source, ".hidden").writeText("hidden local work\n")
        File(source, "nested").mkdir()
        File(source, "nested/binary.bin").writeBytes(byteArrayOf(0, 1, 2, -1))
        File(source, "empty-dir").mkdir()
        return config
    }

    private fun privateSource(config: RepositoryConfig) = resolver().resolve(config)
    private fun finalDirectory(config: RepositoryConfig) =
        File(documents, "RobGit/${sharedDirectoryName(config.displayName, config.id)}")

    @Test fun successCopiesTrackedUntrackedHiddenBinaryChangesAndLocalCommitsWithoutChangingSource() {
        val config = fixture()
        val source = privateSource(config)
        val sourceHead = Git.open(source).use { it.repository.resolve("HEAD").name }
        val sourceReadme = File(source, "README.md").readBytes()
        var analyzed: RepositoryStateSnapshot? = null
        val result = manager().migrate(config.id, onAnalyzed = { analyzed = it })
        val final = finalDirectory(config)
        assertEquals(MigrationPhase.COMPLETED, result.phase)
        assertEquals(WorkspaceLocation.SHARED_DOCUMENTS, registry().load().selected!!.workspaceLocation)
        assertEquals(final.canonicalFile, resolver().resolve(registry().load().selected!!))
        listOf("README.md", "local-commit.txt", "staged.txt", "untracked.txt", ".hidden", "nested/binary.bin").forEach {
            assertArrayEquals(it, File(source, it).readBytes(), File(final, it).readBytes())
        }
        assertTrue(File(final, "empty-dir").isDirectory)
        assertArrayEquals(sourceReadme, File(source, "README.md").readBytes())
        assertEquals(sourceHead, Git.open(final).use { it.repository.resolve("HEAD").name })
        assertEquals(sourceHead, Git.open(source).use { it.repository.resolve("HEAD").name })
        assertEquals(1, analyzed?.ahead)
        assertTrue(analyzed?.changes?.modifiedFiles?.contains("README.md") == true)
        Git.open(final).use { git ->
            val status = git.status().call()
            assertTrue(status.added.contains("staged.txt"))
            assertTrue(status.missing.contains("tracked-delete.txt"))
        }
        assertNotNull(manager().receipt(config.id))
        assertTrue(source.isDirectory)
        assertFalse(File(documents, "RobGit/${result.temporaryDirectory}").exists())
    }

    @Test fun deniedPermissionDoesNotCreateJournalOrUsePrivateFallback() {
        val config = fixture()
        assertThrows(WorkspaceAccessException::class.java) { manager(access = false).migrate(config.id) }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertTrue(privateSource(config).isDirectory)
        assertTrue(manager().pending().isEmpty())
    }

    @Test fun existingFinalFolderIsNeverOverwritten() {
        val config = fixture()
        val final = finalDirectory(config).apply { mkdirs() }
        File(final, "foreign.txt").writeText("keep")
        assertThrows(IllegalStateException::class.java) { manager().migrate(config.id) }
        assertEquals("keep", File(final, "foreign.txt").readText())
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
    }

    @Test fun missingTrackingBranchBlocksMigrationBeforeCopy() {
        val config = fixture()
        Git.open(privateSource(config)).use { git ->
            git.repository.config.unset("branch", "main", "merge")
            git.repository.config.save()
        }
        assertThrows(IllegalStateException::class.java) { manager().migrate(config.id) }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertFalse(finalDirectory(config).exists())
    }

    @Test fun interruptedCopyResumesUsingSameTemporaryDirectoryAndKeepsBackup() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.COPYING) error("simulated process death") }
        }
        val pending = manager().pending().single()
        assertEquals(MigrationPhase.COPYING, pending.phase)
        val temp = File(documents, "RobGit/${pending.temporaryDirectory}").apply { mkdirs() }
        File(temp, "README.md").writeText("partial copy")
        val result = manager().migrate(config.id)
        assertEquals(MigrationPhase.COMPLETED, result.phase)
        assertEquals(File(privateSource(config), "README.md").readText(), File(finalDirectory(config), "README.md").readText())
        assertTrue(privateSource(config).isDirectory)
    }

    @Test fun interruptedVerificationLeavesRegistryPrivateThenResumes() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.VERIFYING_TEMP) error("simulated process death") }
        }
        assertEquals(MigrationPhase.VERIFYING_TEMP, manager().pending().single().phase)
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun interruptedFinalizationDoesNotSwitchRegistryUntilResume() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.FINALIZING) error("simulated process death") }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun finalFolderAppearingDuringFinalizationIsNeverReplaced() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { phase ->
                if (phase == MigrationPhase.FINALIZING) {
                    val final = finalDirectory(config).apply { mkdirs() }
                    File(final, "foreign.txt").writeText("keep")
                }
            }
        }
        assertEquals("keep", File(finalDirectory(config), "foreign.txt").readText())
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertTrue(privateSource(config).isDirectory)
    }

    @Test fun interruptionAfterRenameKeepsRegistryPrivateAndResumesFromFinal() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.VERIFYING_FINAL) error("simulated process death") }
        }
        assertTrue(finalDirectory(config).isDirectory)
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun interruptionBeforeRegistrySwitchKeepsPrivateThenResumes() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.SWITCHING_REGISTRY) error("simulated process death") }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun registryWriteFailureLeavesOldConfigAndFinalCopyForResume() {
        val config = fixture()
        val failingStore = RepositoryRegistry(registryFile, privateRoot) { error("write failure") }
        assertThrows(IllegalStateException::class.java) { manager(store = failingStore).migrate(config.id) }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertTrue(finalDirectory(config).isDirectory)
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun changedSourceDuringCopyAbortsWithoutSwitch() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.COPYING) File(privateSource(config), "README.md").writeText("external change") }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertTrue(privateSource(config).isDirectory)
        assertThrows(IllegalStateException::class.java) { manager().migrate(config.id) }
    }

    @Test fun missingFileInTemporaryCopyFailsVerificationWithoutSwitch() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { phase ->
                if (phase == MigrationPhase.VERIFYING_TEMP) {
                    val temp = File(documents, "RobGit/${manager().pending().single().temporaryDirectory}")
                    File(temp, "untracked.txt").delete()
                }
            }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
    }

    @Test fun differentContentInTemporaryCopyFailsVerificationWithoutSwitch() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { phase ->
                if (phase == MigrationPhase.VERIFYING_TEMP) {
                    val temp = File(documents, "RobGit/${manager().pending().single().temporaryDirectory}")
                    File(temp, "untracked.txt").writeText("tampered")
                }
            }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
    }

    @Test fun remoteAnalysisFailureAfterRegistrySwitchRemainsPendingAndNeverFallsBack() {
        val config = fixture()
        val failingGateway = object : RepositoryRemoteGateway {
            override fun fetch(git: Git, credentials: CredentialsProvider?): Unit {
                File(git.repository.directory, "FETCH_HEAD").writeText("remote metadata changed after switch")
                error("remote unavailable")
            }
            override fun pushMain(git: Git, credentials: CredentialsProvider) = PushTransportResult(PushTransportOutcome.ERROR)
        }
        assertThrows(IllegalStateException::class.java) { manager(gateway = failingGateway).migrate(config.id) }
        val active = registry().load().selected!!
        assertEquals(WorkspaceLocation.SHARED_DOCUMENTS, active.workspaceLocation)
        assertEquals(finalDirectory(config).canonicalFile, resolver().resolve(active))
        assertTrue(manager().pending().isNotEmpty())
        assertEquals(MigrationPhase.COMPLETED, manager().migrate(config.id).phase)
    }

    @Test fun permissionRevokedDuringCopyLeavesRegistryPrivateAndCanResumeAfterGrant() {
        val config = fixture()
        var allowed = true
        val migration = RepositoryMigrationManager(registry(),
            RepositoryWorkspaceResolver(privateRoot, documents) { allowed }, journalRoot, offlineGateway)
        assertThrows(WorkspaceAccessException::class.java) {
            migration.migrate(config.id) { if (it == MigrationPhase.COPYING) allowed = false }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        allowed = true
        assertEquals(MigrationPhase.COMPLETED, migration.migrate(config.id).phase)
    }

    @Test fun finalCopyCorruptionBeforeRegistrySwitchLeavesPrivateWorkspaceActive() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) {
                if (it == MigrationPhase.VERIFYING_FINAL) File(finalDirectory(config), "untracked.txt").writeText("corrupt")
            }
        }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
        assertTrue(privateSource(config).isDirectory)
    }

    @Test fun simultaneousMigrationIsRejectedByPrivateFileLock() {
        val config = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failure: Throwable? = null
        val first = Thread {
            try {
                manager().migrate(config.id) {
                    if (it == MigrationPhase.PLANNED) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
                }
            } catch (error: Throwable) { failure = error }
        }
        first.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { manager().migrate(config.id) }
        } finally {
            release.countDown()
            first.join(10_000)
        }
        assertNull(failure)
        assertEquals(MigrationPhase.COMPLETED, manager().receipt(config.id)?.phase)
    }

    @Test fun completedJournalIsAnIdempotentReceiptEvenIfRegistryEntryIsRemoved() {
        val config = fixture()
        val first = manager().migrate(config.id)
        assertEquals(first, manager().migrate(config.id))
        registry().remove(config.id)
        assertTrue(finalDirectory(config).isDirectory)
        assertTrue(privateSource(config).isDirectory)
        assertEquals(MigrationPhase.COMPLETED, manager().receipt(config.id)?.phase)
    }

    @Test fun corruptJournalBlocksRecoveryWithoutChangingRegistry() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.PLANNED) error("stop") }
        }
        val file = journalRoot.listFiles()!!.single { it.name.endsWith(".properties") }
        file.writeText("phase=INVALID\n")
        assertThrows(Exception::class.java) { manager().pending() }
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
    }

    @Test fun symlinkInSourceIsRejectedWithoutFollowingIt() {
        val config = fixture()
        val outside = File(folder.root, "outside.txt").apply { writeText("outside") }
        val link = File(privateSource(config), "outside-link")
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        catch (unsupported: Exception) { assumeNoException(unsupported) }
        assertThrows(IllegalStateException::class.java) { manager().migrate(config.id) }
        assertEquals("outside", outside.readText())
        assertEquals(WorkspaceLocation.APP_PRIVATE, registry().load().selected!!.workspaceLocation)
    }

    @Test fun gitVerificationDetectsHeadBranchRemoteTrackingAndWorkingTreeDifferences() {
        val config = fixture()
        assertThrows(IllegalStateException::class.java) {
            manager().migrate(config.id) { if (it == MigrationPhase.VERIFYING_TEMP) error("pause before verification") }
        }
        val temp = File(documents, "RobGit/${manager().pending().single().temporaryDirectory}")
        val source = privateSource(config)
        manager().verifyGitEquivalent(source, temp, "main")
        Git.open(temp).use { git ->
            git.repository.config.setString("remote", "origin", "url", "https://github.com/other/repo.git")
            git.repository.config.save()
        }
        assertThrows(IllegalStateException::class.java) { manager().verifyGitEquivalent(source, temp, "main") }
        Git.open(temp).use { git ->
            git.repository.config.setString("remote", "origin", "url", config.remoteUrl)
            git.repository.config.setString("branch", "main", "merge", "refs/heads/other")
            git.repository.config.save()
        }
        assertThrows(IllegalStateException::class.java) { manager().verifyGitEquivalent(source, temp, "main") }
        Git.open(temp).use { git ->
            git.repository.config.setString("branch", "main", "merge", "refs/heads/main")
            git.repository.config.save()
        }
        File(temp, "different-untracked.txt").writeText("different working tree")
        assertThrows(IllegalStateException::class.java) { manager().verifyGitEquivalent(source, temp, "main") }
        File(temp, "different-untracked.txt").delete()
        Git.open(temp).use { git ->
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef("refs/remotes/origin/main").call()
        }
        assertThrows(IllegalStateException::class.java) { manager().verifyGitEquivalent(source, temp, "main") }
        File(temp, ".git/HEAD").writeText("ref: refs/heads/other\n")
        assertThrows(IllegalStateException::class.java) { manager().verifyGitEquivalent(source, temp, "main") }
    }
}
