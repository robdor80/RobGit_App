package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryRegistryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun registry(): RepositoryRegistry = RepositoryRegistry(
        File(folder.root, "registry.properties"),
        File(folder.root, "repos"),
    )

    private fun emptyRegistry(): RepositoryRegistry = registry().also {
        it.remove(it.load().selectedRepositoryId!!)
    }

    @Test fun firstLoadMigratesLegacyExactlyOnce() {
        val store = registry()
        val first = store.load()
        val second = store.load()
        assertEquals(first, second)
        assertEquals(1, first.repositories.size)
        assertEquals("Robgit.pruebas", first.selected?.displayName)
        assertEquals("https://github.com/robdor80/Robgit.pruebas.git", first.selected?.remoteUrl)
        assertEquals("main", first.selected?.branch)
        assertEquals("robgit-pruebas", first.selected?.localDirectoryName)
    }

    @Test fun legacyDirectoryIsReusedWithoutModification() {
        val old = File(folder.root, "repos/robgit-pruebas").apply { mkdirs() }
        val marker = File(old, "keep.txt").apply { writeText("unchanged") }
        val config = registry().load().selected!!
        assertEquals(old.canonicalFile, registry().directoryFor(config))
        assertEquals("unchanged", marker.readText())
    }

    @Test fun missingLegacyDirectoryRemainsConfiguredAndUnprepared() {
        val store = registry()
        val config = store.load().selected!!
        assertFalse(store.directoryFor(config).exists())
    }

    @Test fun removeLastPersistsEmptyCatalogWithoutRepeatingMigration() {
        val store = registry()
        val id = store.load().selectedRepositoryId!!
        assertTrue(store.remove(id).repositories.isEmpty())
        assertNull(registry().load().selected)
    }

    @Test fun addAndSelectionSurviveStoreRestart() {
        val store = emptyRegistry()
        val first = store.add("  Nimroel RPG  ", "https://github.com/owner/nimroel", "main").selected!!
        val second = store.add("Cuadrante", "https://github.com/owner/cuadrante.git", "develop").selected!!
        assertEquals("Nimroel RPG", first.displayName)
        assertEquals(second.id, registry().load().selectedRepositoryId)
        store.select(first.id)
        assertEquals(first.id, registry().load().selectedRepositoryId)
        assertEquals(2, registry().load().repositories.size)
    }

    @Test fun urlFormsNormalizeAndDuplicateIsRejected() {
        val store = emptyRegistry()
        val first = store.add("A", "https://github.com/owner/repo", "main").selected!!
        assertEquals("https://github.com/owner/repo.git", first.remoteUrl)
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.add("B", "https://github.com/owner/repo.git", "main")
        }
        assertEquals("Este repositorio ya está configurado en RobGit.", error.message)
        assertEquals(1, store.load().repositories.size)
    }

    @Test fun sameRemoteCanUseDifferentBranchesAndDirectories() {
        val store = emptyRegistry()
        val main = store.add("A", "https://github.com/owner/repo", "main").selected!!
        val develop = store.add("B", "https://github.com/owner/repo.git", "develop").selected!!
        assertNotEquals(main.localDirectoryName, develop.localDirectoryName)
        assertNotEquals(store.directoryFor(main), store.directoryFor(develop))
    }

    @Test fun invalidNamesUrlsAndBranchesAreRejected() {
        val store = emptyRegistry()
        assertThrows(IllegalArgumentException::class.java) { store.add("  ", "https://github.com/o/r", "main") }
        listOf("", "http://github.com/o/r", "https://example.com/o/r", "https://github.com/o", "https://github.com/o/r/extra", "https://github.com/o/../r").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { store.add("X", url, "main") }
        }
        listOf("", "  ", "../main", "main..other", "branch name", "branch.lock").forEach { branch ->
            assertThrows(branch, IllegalArgumentException::class.java) { store.add("X", "https://github.com/o/r", branch) }
        }
    }

    @Test fun generatedDirectoryCannotEscapeRepositoryRoot() {
        val store = emptyRegistry()
        val config = store.add("../../not-a-path", "https://github.com/o/r", "main").selected!!
        assertTrue(config.localDirectoryName.matches(Regex("repo-[a-f0-9]{32}")))
        assertEquals(File(folder.root, "repos").canonicalFile, store.directoryFor(config).parentFile)
        assertThrows(IllegalArgumentException::class.java) {
            store.directoryFor(config.copy(localDirectoryName = "../outside"))
        }
    }

    @Test fun removingSelectionChoosesAnotherAndNeverDeletesFiles() {
        val store = emptyRegistry()
        val first = store.add("A", "https://github.com/o/a", "main").selected!!
        val second = store.add("B", "https://github.com/o/b", "main").selected!!
        val directory = store.directoryFor(second).apply { mkdirs() }
        val marker = File(directory, "keep.txt").apply { writeText("safe") }
        val after = store.remove(second.id)
        assertEquals(first.id, after.selectedRepositoryId)
        assertEquals("safe", marker.readText())
        assertTrue(directory.isDirectory)
    }
}
