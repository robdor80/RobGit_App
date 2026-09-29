package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedWorkspaceProbeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun documentsRootAndTestRepositoryAreResolvedWithinTheirParents() {
        val documents = File(folder.root, "Documents")
        val root = resolveRobGitDocumentsRoot(documents)
        val repository = resolveWorkspaceChild(root, SHARED_WORKSPACE_DIRECTORY)
        assertEquals(File(documents, "RobGit").canonicalFile, root)
        assertEquals(root, repository.canonicalFile.parentFile)
        assertFalse(root.exists())
        assertFalse(repository.exists())
    }

    @Test fun pathTraversalCannotEscapeWorkspaceRoot() {
        val root = File(folder.root, "Documents/RobGit")
        assertThrows(IllegalArgumentException::class.java) { resolveWorkspaceChild(root, "../outside") }
        assertThrows(IllegalArgumentException::class.java) { resolveWorkspaceChild(root, "nested/repo") }
    }

    @Test fun permissionPolicyBlocksWithoutPermissionAndAllowsWithPermission() {
        assertFalse(workspaceOperationAllowed(false))
        assertTrue(workspaceOperationAllowed(true))
    }

    @Test fun missingPermissionBlocksBeforeCreatingExternalWorkspace() {
        val docs = File(folder.root, "Documents")
        val remote = createRemote("permission")
        val probe = probe(docs, permission = false, remote = remote)
        val result = probe.prepare()
        assertFalse(result.success)
        assertTrue(result.message.contains("no está concedido"))
        assertFalse(File(docs, "RobGit").exists())
    }

    @Test fun permissionGrantedAllowsJGitToPrepareTheWorkspace() {
        val docs = File(folder.root, "Documents")
        val probe = probe(docs, remote = createRemote("allowed"))
        val result = probe.prepare()
        assertTrue(result.message, result.success)
        assertTrue(File(probe.repositoryDirectory, ".git").isDirectory)
        Git.open(probe.repositoryDirectory).use { git -> assertEquals("main", git.repository.branch) }
    }

    @Test fun existingNonGitDirectoryIsPreserved() {
        val docs = File(folder.root, "Documents")
        val probe = probe(docs, remote = createRemote("invalid"))
        val marker = File(probe.repositoryDirectory, "keep.txt")
        probe.repositoryDirectory.mkdirs()
        marker.writeText("preserve")
        val result = probe.prepare()
        assertFalse(result.success)
        assertEquals("preserve", marker.readText())
    }

    @Test fun validExistingRepositoryIsReusedAndFilesystemProbeLeavesNoWorktreeChanges() {
        val docs = File(folder.root, "Documents")
        val remote = createRemote("reuse")
        val probe = probe(docs, remote = remote)
        assertTrue(probe.prepare().success)
        val before = Git.open(probe.repositoryDirectory).use { it.repository.resolve("HEAD")!!.name }
        assertTrue(probe.prepare().success)
        assertEquals(before, Git.open(probe.repositoryDirectory).use { it.repository.resolve("HEAD")!!.name })
        val fsResult = probe.probeFilesystem()
        assertTrue(fsResult.message, fsResult.success)
        assertTrue(Git.open(probe.repositoryDirectory).use { it.status().call().isClean })
    }

    @Test fun unexpectedRemoteBlocksAndPreservesExistingRepository() {
        val docs = File(folder.root, "Documents")
        val first = createRemote("expected")
        val other = createRemote("other")
        val probe = probe(docs, remote = first)
        val otherService = RepositoryStateService(other.toURI().toString())
        assertTrue(otherService.prepare(probe.repositoryDirectory).success)
        val head = Git.open(probe.repositoryDirectory).use { it.repository.resolve("HEAD")!!.name }
        val result = probe.prepare()
        assertFalse(result.success)
        assertEquals(head, Git.open(probe.repositoryDirectory).use { it.repository.resolve("HEAD")!!.name })
    }

    @Test fun synchronizationWithDisjointLocalAndRemoteChangesReportsCombinedSuccess() {
        val remote = createRemote("combined-sync")
        val probe = probe(File(folder.root, "Documents"), remote = remote)
        assertTrue(probe.prepare().success)
        File(probe.repositoryDirectory, "local.md").writeText("local work")

        val writer = File(remote.parentFile, "writer")
        Git.open(writer).use { git ->
            File(writer, "README.md").writeText("remote work")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("remote change").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.push().setRemote("origin").call()
        }

        val result = probe.synchronize("local-test-token".toCharArray(), "Guardar trabajo local")
        assertTrue(result.message, result.success)
        assertEquals(SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED.name, result.outcome)
        assertEquals(CommitRelation.SYNCHRONIZED, result.state?.relation)
        assertEquals(false, result.state?.changes?.hasChanges)
        assertEquals("local work", File(probe.repositoryDirectory, "local.md").readText())
        assertEquals("remote work", File(probe.repositoryDirectory, "README.md").readText())
        Git.open(probe.repositoryDirectory).use { git ->
            assertTrue(git.status().call().isClean)
            Git.open(remote).use { remoteGit ->
                assertEquals(remoteGit.repository.resolve("refs/heads/main"), git.repository.resolve("HEAD"))
            }
        }
    }

    @Test fun wrongConfiguredBranchBlocksExistingRepositoryAndMissingBranchDoesNotCreateTarget() {
        val docs = File(folder.root, "Documents")
        val remote = createRemote("branch")
        val standard = probe(docs, remote = remote)
        assertTrue(standard.prepare().success)
        val wrongBranch = probe(docs, remote = remote, branch = "develop")
        assertFalse(wrongBranch.prepare().success)

        val missing = File(folder.root, "another-documents")
        val missingBranch = probe(missing, remote = remote, branch = "absent")
        assertFalse(missingBranch.prepare().success)
        assertFalse(missingBranch.repositoryDirectory.exists())
    }

    @Test fun prototypeDoesNotChangeProductionRegistryOrPrivateRepository() {
        val appFiles = File(folder.root, "app-private")
        val privateRepos = File(appFiles, "repos")
        val registry = RepositoryRegistry(File(appFiles, "repositories.properties"), privateRepos)
        val originalCatalog = registry.load()
        val privateLegacyDirectory = registry.directoryFor(originalCatalog.selected!!)
        privateLegacyDirectory.mkdirs()
        val marker = File(privateLegacyDirectory, "untouched.txt").apply { writeText("private repo") }
        val registryBefore = File(appFiles, "repositories.properties").readBytes()

        val probe = probe(File(folder.root, "Documents"), remote = createRemote("independence"))
        assertTrue(probe.prepare().success)
        assertArrayEquals(registryBefore, File(appFiles, "repositories.properties").readBytes())
        assertEquals("private repo", marker.readText())
        assertEquals("robgit-pruebas", registry.load().selected?.localDirectoryName)
    }

    @Test fun unavailableWorkspaceReturnsSafeError() {
        val documentsIsAFile = File(folder.root, "not-a-directory").apply { writeText("file") }
        val probe = probe(documentsIsAFile, remote = createRemote("io"))
        val result = probe.probeFilesystem()
        assertFalse(result.success)
        assertTrue(result.message.isNotBlank())
        assertTrue(result.repositoryPath.contains(SHARED_WORKSPACE_DIRECTORY))
    }

    private fun probe(documents: File, permission: Boolean = true, remote: File, branch: String = "main") =
        SharedWorkspaceProbe(documents, { permission }, remote.toURI().toString(), branch)

    private fun createRemote(name: String): File {
        val directory = folder.newFolder(name)
        val remote = File(directory, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val writer = File(directory, "writer")
        Git.init().setInitialBranch("main").setDirectory(writer).call().use { git ->
            File(writer, "README.md").writeText("# $name")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
            git.push().setRemote("origin").call()
        }
        return remote
    }
}
