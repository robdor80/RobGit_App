package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class RepositoryWorkspaceResolverTest {
    @get:Rule val folder = TemporaryFolder()

    private val privateRoot get() = File(folder.root, "files/repos")
    private val documents get() = File(folder.root, "Documents")
    private val registryFile get() = File(folder.root, "files/repositories.properties")
    private fun registry() = RepositoryRegistry(registryFile, privateRoot)
    private fun resolver(access: Boolean = true) = RepositoryWorkspaceResolver(privateRoot, documents) { access }

    @Test fun oldPropertyFileWithoutLocationKeepsPrivateDirectoryAndSelection() {
        requireNotNull(registryFile.parentFile).mkdirs()
        val values = Properties().apply {
            setProperty("count", "2")
            setProperty("selected", "0123456789abcdef0123456789abcdef")
            setProperty("0.id", "legacy-robgit-pruebas")
            setProperty("0.name", "Robgit.pruebas")
            setProperty("0.url", "https://github.com/robdor80/Robgit.pruebas.git")
            setProperty("0.branch", "main")
            setProperty("0.directory", "robgit-pruebas")
            setProperty("1.id", "0123456789abcdef0123456789abcdef")
            setProperty("1.name", "Robgit.prueba2")
            setProperty("1.url", "https://github.com/robdor80/robgit.prueba2.git")
            setProperty("1.branch", "main")
            setProperty("1.directory", "repo-0123456789abcdef0123456789abcdef")
        }
        registryFile.outputStream().use { values.store(it, null) }
        val catalog = registry().load()
        assertEquals("Robgit.prueba2", catalog.selected?.displayName)
        assertTrue(catalog.repositories.all { it.workspaceLocation == WorkspaceLocation.APP_PRIVATE })
        assertEquals(File(privateRoot, "repo-0123456789abcdef0123456789abcdef").canonicalFile,
            resolver().resolve(requireNotNull(catalog.selected)))
    }

    @Test fun newRepositoryUsesSharedDocumentsAndPersistsLocation() {
        val store = registry()
        val new = store.add("Cuadrante App", "https://github.com/o/r", "main").selected!!
        assertEquals(WorkspaceLocation.SHARED_DOCUMENTS, new.workspaceLocation)
        assertEquals(File(documents, "RobGit").canonicalFile, resolver().resolve(new).parentFile)
        assertEquals(new, registry().load().selected)
    }

    @Test fun safeDirectoryIsStableAndDistinctForDifferentRepositories() {
        val store = registry()
        val first = store.add("Café/RPG ../../", "https://github.com/o/one", "main").selected!!
        val second = store.add("Café/RPG ../../", "https://github.com/o/two", "main").selected!!
        assertEquals(sharedDirectoryName(first.displayName, first.id), first.localDirectoryName)
        assertTrue(first.localDirectoryName.matches(Regex("[a-z0-9-]+")))
        assertNotEquals(first.localDirectoryName, second.localDirectoryName)
        assertEquals(first.localDirectoryName, registry().load().repositories.first { it.id == first.id }.localDirectoryName)
    }

    @Test fun traversalAndArbitraryAbsoluteDirectoryAreRejected() {
        val new = registry().add("Safe", "https://github.com/o/r", "main").selected!!
        listOf("../outside", "/tmp/other", "safe/../../outside", ".").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) {
                resolver().resolve(new.copy(localDirectoryName = bad))
            }
        }
    }

    @Test fun deniedPermissionBlocksSharedWithoutPrivateFallback() {
        val new = registry().add("Safe", "https://github.com/o/r", "main").selected!!
        File(privateRoot, new.localDirectoryName).apply { mkdirs(); resolve("keep.txt").writeText("private") }
        assertThrows(WorkspaceAccessException::class.java) { resolver(access = false).resolve(new) }
        assertTrue(File(privateRoot, new.localDirectoryName).isDirectory)
    }

    @Test fun mixedLocationsResolveIndependentlyAndSelectionDoesNotMoveFiles() {
        val store = registry()
        val old = store.load().selected!!
        val new = store.add("New", "https://github.com/o/r", "main").selected!!
        assertEquals(privateRoot.canonicalFile, resolver().resolve(old).parentFile)
        assertEquals(File(documents, "RobGit").canonicalFile, resolver().resolve(new).parentFile)
        store.select(old.id)
        assertEquals(old.id, registry().load().selectedRepositoryId)
        assertEquals(privateRoot.canonicalFile, resolver().resolve(old).parentFile)
        assertEquals(File(documents, "RobGit").canonicalFile, resolver().resolve(new).parentFile)
    }

    @Test fun missingPermissionNeverBlocksLegacyPrivateRepository() {
        val old = registry().load().selected!!
        assertEquals(File(privateRoot, "robgit-pruebas").canonicalFile, resolver(false).resolve(old))
    }

    @Test fun existingSharedFolderIsNotOverwrittenDuringPrepare() {
        val config = registry().add("New", "https://github.com/o/r", "main").selected!!
        val directory = resolver().resolve(config).apply { mkdirs() }
        File(directory, "keep.txt").writeText("private work")
        val result = RepositoryStateService(config.remoteUrl, branch = config.branch).prepare(directory)
        assertFalse(result.success)
        assertEquals("private work", File(directory, "keep.txt").readText())
    }

    @Test fun matchingExistingSharedRepositoryOpensWithoutReplacingFiles() {
        val config = registry().add("New", "https://github.com/o/r", "main").selected!!
        val directory = resolver().resolve(config)
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            File(directory, "keep.txt").writeText("local changes")
            git.add().addFilepattern("keep.txt").call()
            val head = git.commit().setMessage("initial").setAuthor("Test", "test@localhost")
                .setCommitter("Test", "test@localhost").call()
            git.repository.config.setString("remote", "origin", "url", config.remoteUrl)
            git.repository.config.save()
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(head.id); update() }
        }
        val result = RepositoryStateService(config.remoteUrl, branch = config.branch).prepare(directory)
        assertTrue(result.success)
        assertFalse(result.cloned)
        assertEquals("local changes", File(directory, "keep.txt").readText())
    }

    @Test fun caseVariantFolderNameCannotCreateAmbiguousSharedDestination() {
        val config = registry().add("New", "https://github.com/o/r", "main").selected!!
        File(documents, "RobGit/${config.localDirectoryName.uppercase()}").mkdirs()
        assertThrows(IllegalArgumentException::class.java) { resolver().resolve(config) }
    }

    @Test fun registryRejectsUnknownLocationWithoutRewritingFile() {
        val store = registry()
        store.load()
        val values = Properties().apply { registryFile.inputStream().use(::load) }
        values.setProperty("0.workspaceLocation", "UNKNOWN")
        registryFile.outputStream().use { values.store(it, null) }
        val original = registryFile.readBytes()
        assertThrows(IllegalStateException::class.java) { registry().load() }
        assertArrayEquals(original, registryFile.readBytes())
    }

    @Test fun atomicReplaceFailurePreservesOriginalRegistryBytes() {
        val store = registry()
        store.load()
        val original = registryFile.readBytes()
        val failing = RepositoryRegistry(registryFile, privateRoot) { error("injected before replace") }
        assertThrows(IllegalStateException::class.java) {
            failing.add("New", "https://github.com/o/r", "main")
        }
        assertArrayEquals(original, registryFile.readBytes())
        assertEquals(store.load().selectedRepositoryId, registry().load().selectedRepositoryId)
    }

    @Test fun registrySwitchIsAtomicAndPreservesOtherSelections() {
        val store = registry()
        val old = store.load().selected!!
        val other = store.add("Other", "https://github.com/o/r", "main").selected!!
        val shared = sharedDirectoryName(old.displayName, old.id)
        val result = store.activateShared(old.id, old.localDirectoryName, shared)
        assertEquals(other.id, result.selectedRepositoryId)
        assertEquals(WorkspaceLocation.SHARED_DOCUMENTS, result.repositories.first { it.id == old.id }.workspaceLocation)
        assertEquals(WorkspaceLocation.SHARED_DOCUMENTS, registry().load().selected!!.workspaceLocation)
        assertThrows(IllegalStateException::class.java) { store.activateShared(old.id, old.localDirectoryName, shared) }
    }

    @Test fun removingPrivateRepositoryDoesNotDeleteDirectory() {
        val store = registry()
        val old = store.load().selected!!
        val directory = resolver().resolve(old).apply { mkdirs() }
        File(directory, "keep.txt").writeText("safe")
        store.remove(old.id)
        assertEquals("safe", File(directory, "keep.txt").readText())
    }
}
