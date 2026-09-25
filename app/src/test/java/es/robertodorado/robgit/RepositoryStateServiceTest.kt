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

    private val service = RepositoryStateService()

    @Test
    fun synchronizedWhenHeadsMatchAndTreeIsClean() {
        val fixture = fixture()

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED, 0, 0)
        assertFalse(state.changes.hasChanges)
    }

    @Test
    fun reportsNewWorkingTreeFile() {
        val fixture = fixture()
        File(fixture.local, "nuevo.txt").writeText("nuevo")

        val state = service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("nuevo.txt"), state.changes.newFiles)
        assertTrue(state.changes.modifiedFiles.isEmpty())
        assertTrue(state.changes.deletedFiles.isEmpty())
    }

    @Test
    fun reportsModifiedWorkingTreeFile() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("modificado")

        val state = service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("tracked.txt"), state.changes.modifiedFiles)
        assertTrue(state.changes.newFiles.isEmpty())
        assertTrue(state.changes.deletedFiles.isEmpty())
    }

    @Test
    fun reportsDeletedWorkingTreeFile() {
        val fixture = fixture()
        assertTrue(File(fixture.local, "tracked.txt").delete())

        val state = service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("tracked.txt"), state.changes.deletedFiles)
        assertTrue(state.changes.newFiles.isEmpty())
        assertTrue(state.changes.modifiedFiles.isEmpty())
    }

    @Test
    fun reportsOneLocalCommitAhead() {
        val fixture = fixture()
        commit(fixture.local, "local-1.txt", "local 1", "local 1")

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, 1, 0)
    }

    @Test
    fun reportsOneRemoteCommitAhead() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote-1.txt", "remote 1", "remote 1")

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, 0, 1)
    }

    @Test
    fun reportsSeveralLocalCommitsAhead() {
        val fixture = fixture()
        repeat(3) { index ->
            commit(fixture.local, "local-$index.txt", "local $index", "local $index")
        }

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, 3, 0)
    }

    @Test
    fun reportsSeveralRemoteCommitsAhead() {
        val fixture = fixture()
        repeat(3) { index ->
            commit(fixture.writer, "remote-$index.txt", "remote $index", "remote $index")
        }
        push(fixture.writer)

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, 0, 3)
    }

    @Test
    fun reportsRealDivergenceAndExclusiveCommitCounts() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "solo local", "solo local")
        commitAndPush(fixture.writer, "remote.txt", "solo remoto", "solo remoto")

        val state = service.refreshState(fixture.local)

        assertState(state, RepositoryStateType.DIVERGED, CommitRelation.DIVERGED, 1, 1)
    }

    @Test
    fun exposesStagedFilesSeparately() {
        val fixture = fixture()
        File(fixture.local, "staged.txt").writeText("staged")
        Git.open(fixture.local).use { it.add().addFilepattern("staged.txt").call() }

        val state = service.refreshState(fixture.local)

        assertEquals(RepositoryStateType.LOCAL_CHANGES, state.type)
        assertEquals(setOf("staged.txt"), state.changes.newFiles)
        assertEquals(setOf("staged.txt"), state.changes.stagedFiles)
    }

    @Test
    fun fetchFailureNeverReportsSynchronizedState() {
        val fixture = fixture()
        Git.open(fixture.local).use { git ->
            git.repository.config.setString(
                "remote",
                "origin",
                "url",
                File(temporaryFolder.root, "missing.git").toURI().toString(),
            )
            git.repository.config.save()
        }

        val state = service.refreshState(fixture.local)

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
        return Fixture(remote, writer, local)
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

    private data class Fixture(val remote: File, val writer: File, val local: File)
}
