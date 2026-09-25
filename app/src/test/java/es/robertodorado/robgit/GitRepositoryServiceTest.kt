package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitRepositoryServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun localDiagnosticCreatesReadableCleanCommit() {
        val directory = File(temporaryFolder.root, "repo")
        val result = GitRepositoryService().runLocalDiagnostic(directory)

        assertNull(result.error)
        assertEquals("main", result.branch)
        assertEquals("Working tree limpio", result.status)
        assertEquals(8, result.steps.size)
        assertTrue(result.lastCommit.contains("Prueba local de JGit"))

        Git.open(directory).use { git ->
            assertTrue(git.status().call().isClean)
            assertEquals(1, git.log().call().count())
            val commit = git.log().setMaxCount(1).call().first()
            val entry = TreeWalk.forPath(git.repository, "diagnostico.txt", commit.tree)
            assertTrue(entry != null)
            entry!!.use {
                val contents = git.repository.open(it.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                assertEquals("Prueba local de JGit en Android\n", contents)
            }
        }
    }

    @Test
    fun refusesToUseExistingDirectory() {
        val directory = temporaryFolder.newFolder("existing")
        val result = GitRepositoryService().runLocalDiagnostic(directory)

        assertFalse(result.error.isNullOrBlank())
        assertTrue(result.steps.isEmpty())
        assertFalse(File(directory, ".git").exists())
    }
}
