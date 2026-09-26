package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryUploadServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun missingTokenBlocksBeforeAnyRemoteOrLocalMutation() {
        val fixture = fixture()
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("sin credencial")

        val result = fixture.service.uploadSafely(
            fixture.local,
            CharArray(0),
            "Cambios desde RobGit",
        )

        assertEquals(UploadOutcome.AUTH_REQUIRED, result.outcome)
        assertEquals(0, fixture.gateway.fetchCount)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertEquals(setOf("tracked.txt"), status(fixture.local).modified)
    }

    @Test
    fun blankCommitMessageBlocksBeforeAnyRemoteOrLocalMutation() {
        val fixture = fixture()
        val token = "token-local".toCharArray()
        File(fixture.local, "tracked.txt").writeText("sin mensaje")

        val result = fixture.service.uploadSafely(fixture.local, token, "   ")

        assertEquals(UploadOutcome.ERROR, result.outcome)
        assertEquals(0, fixture.gateway.fetchCount)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(setOf("tracked.txt"), status(fixture.local).modified)
        assertTrue(token.all { it == '\u0000' })
    }

    @Test
    fun synchronizedCleanRepositoryHasNothingToUpload() {
        val fixture = fixture()
        val localHead = head(fixture.local)
        val remoteHead = bareHead(fixture.remote)
        val commits = commitCount(fixture.local)

        val result = upload(fixture)

        assertEquals(UploadOutcome.NOTHING_TO_UPLOAD, result.outcome)
        assertFalse(result.commitCreated)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertEquals(remoteHead, bareHead(fixture.remote))
        assertEquals(commits, commitCount(fixture.local))
        assertTrue(status(fixture.local).isClean)
    }

    @Test
    fun modifiedFileCreatesOneCommitAndPushesExactContents() {
        val fixture = fixture()
        Git.open(fixture.local).use { git ->
            git.repository.config.setString("user", null, "name", "Nombre configurado")
            git.repository.config.setString("user", null, "email", "configurado@example.test")
            git.repository.config.save()
        }
        val commitsBefore = commitCount(fixture.local)
        File(fixture.local, "tracked.txt").writeText("contenido modificado")

        val result = upload(fixture, "Modificar archivo")

        assertSuccessfulUpload(fixture, result, commitsBefore + 1)
        assertTrue(result.commitCreated)
        assertEquals("contenido modificado", readBareFile(fixture.remote, "tracked.txt"))
        Git.open(fixture.local).use { git ->
            val commit = git.log().setMaxCount(1).call().first()
            assertEquals("Nombre configurado", commit.authorIdent.name)
            assertEquals("configurado@example.test", commit.authorIdent.emailAddress)
        }
    }

    @Test
    fun untrackedFileIsIncludedAndPushed() {
        val fixture = fixture()
        Git.open(fixture.local).use { git ->
            git.repository.config.setString("user", null, "name", "")
            git.repository.config.setString("user", null, "email", "sin-email-valido")
            git.repository.config.save()
        }
        File(fixture.local, "nuevo.txt").writeText("archivo nuevo")

        val result = upload(fixture)

        assertSuccessfulUpload(fixture, result, 2)
        assertEquals("archivo nuevo", readBareFile(fixture.remote, "nuevo.txt"))
        Git.open(fixture.local).use { git ->
            val commit = git.log().setMaxCount(1).call().first()
            assertEquals("RobGit Android", commit.authorIdent.name)
            assertEquals("robgit@localhost", commit.authorIdent.emailAddress)
        }
    }

    @Test
    fun trackedDeletionIsIncludedAndPushed() {
        val fixture = fixture()
        assertTrue(File(fixture.local, "tracked.txt").delete())

        val result = upload(fixture)

        assertSuccessfulUpload(fixture, result, 2)
        assertEquals(null, readBareFile(fixture.remote, "tracked.txt"))
    }

    @Test
    fun stagedAndUnstagedChangesAreCommittedWithoutDataLoss() {
        val fixture = fixture()
        val mixed = File(fixture.local, "mixed.txt")
        mixed.writeText("versión staged")
        Git.open(fixture.local).use { it.add().addFilepattern("mixed.txt").call() }
        mixed.writeText("versión final unstaged")
        File(fixture.local, "tracked.txt").writeText("otro cambio")

        val result = upload(fixture)

        assertSuccessfulUpload(fixture, result, 2)
        assertEquals("versión final unstaged", readBareFile(fixture.remote, "mixed.txt"))
        assertEquals("otro cambio", readBareFile(fixture.remote, "tracked.txt"))
        assertTrue(status(fixture.local).isClean)
    }

    @Test
    fun existingLocalCommitsArePushedWithoutExtraCommit() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "commit local", "commit local")
        val localHead = head(fixture.local)
        val commitsBefore = commitCount(fixture.local)

        val result = upload(fixture)

        assertEquals(UploadOutcome.SUCCESS, result.outcome)
        assertFalse(result.commitCreated)
        assertEquals(localHead, result.attemptedHead)
        assertEquals(commitsBefore, commitCount(fixture.local))
        assertEquals(localHead, bareHead(fixture.remote))
        assertEquals("commit local", readBareFile(fixture.remote, "local.txt"))
    }

    @Test
    fun localCommitsAndPendingChangesCreateExactlyOneAdditionalCommit() {
        val fixture = fixture()
        commit(fixture.local, "local-commit.txt", "primero", "commit local")
        val commitsBefore = commitCount(fixture.local)
        File(fixture.local, "pendiente.txt").writeText("segundo")

        val result = upload(fixture)

        assertSuccessfulUpload(fixture, result, commitsBefore + 1)
        assertTrue(result.commitCreated)
        assertEquals(2, result.commitsUploaded)
        assertEquals("primero", readBareFile(fixture.remote, "local-commit.txt"))
        assertEquals("segundo", readBareFile(fixture.remote, "pendiente.txt"))
    }

    @Test
    fun remoteAheadBlocksBeforeStageCommitOrPush() {
        val fixture = fixture()
        commitAndPush(fixture.writer, "remote.txt", "remoto", "remoto")
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("cambio local intacto")

        val result = upload(fixture)

        assertEquals(UploadOutcome.BLOCKED_REMOTE_AHEAD, result.outcome)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertEquals("cambio local intacto", File(fixture.local, "tracked.txt").readText())
        val currentStatus = status(fixture.local)
        assertEquals(setOf("tracked.txt"), currentStatus.modified)
        assertTrue(currentStatus.changed.isEmpty())
    }

    @Test
    fun divergenceBlocksWithoutMergeOrPush() {
        val fixture = fixture()
        commit(fixture.local, "local.txt", "solo local", "local")
        val localHead = head(fixture.local)
        val localCommits = commitCount(fixture.local)
        commitAndPush(fixture.writer, "remote.txt", "solo remoto", "remoto")
        val remoteHead = bareHead(fixture.remote)

        val result = upload(fixture)

        assertEquals(UploadOutcome.BLOCKED_DIVERGED, result.outcome)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertEquals(remoteHead, bareHead(fixture.remote))
        assertEquals(localCommits, commitCount(fixture.local))
        assertFalse(File(fixture.local, "remote.txt").exists())
        assertTrue(status(fixture.local).isClean)
    }

    @Test
    fun localConflictBlocksWithoutAnyRemoteOperation() {
        val fixture = fixture()
        File(fixture.local, "tracked.txt").writeText("versión local")
        commit(fixture.local, "tracked.txt", "versión local", "local conflictivo")
        commitAndPush(fixture.writer, "tracked.txt", "versión remota", "remoto conflictivo")
        Git.open(fixture.local).use { git ->
            JGitRepositoryRemoteGateway().fetch(git, null)
            val remote = requireNotNull(git.repository.resolve("refs/remotes/origin/main"))
            val merge = git.merge().include(remote).call()
            assertFalse(merge.mergeStatus.isSuccessful)
        }
        val localHead = head(fixture.local)

        val result = upload(fixture)

        assertEquals(UploadOutcome.BLOCKED_CONFLICTS, result.outcome)
        assertEquals(0, fixture.gateway.fetchCount)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertTrue(status(fixture.local).conflicting.contains("tracked.txt"))
    }

    @Test
    fun staleShownStateIsRevalidatedBeforeMutation() {
        val fixture = fixture()
        val shown = fixture.service.refreshState(fixture.local)
        assertEquals(RepositoryStateType.SYNCHRONIZED, shown.type)
        commitAndPush(fixture.writer, "remote.txt", "nuevo remoto", "remoto posterior")
        File(fixture.local, "tracked.txt").writeText("local posterior")
        val localHead = head(fixture.local)

        val result = upload(fixture)

        assertEquals(UploadOutcome.BLOCKED_REMOTE_AHEAD, result.outcome)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        assertEquals("local posterior", File(fixture.local, "tracked.txt").readText())
        assertEquals(setOf("tracked.txt"), status(fixture.local).modified)
    }

    @Test
    fun remoteChangeAfterCommitBeforePushKeepsLocalCommit() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("trabajo local")
        fixture.gateway.beforeFetch = { count ->
            if (count == 2) {
                commitAndPush(fixture.writer, "remote.txt", "cambio concurrente", "concurrente")
            }
        }

        val result = upload(fixture)

        assertEquals(UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertTrue(result.commitCreated)
        assertTrue(result.message.contains("guardado localmente"))
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(result.attemptedHead, head(fixture.local))
        assertNotEquals(head(fixture.local), bareHead(fixture.remote))
        assertEquals("trabajo local", File(fixture.local, "local.txt").readText())
        assertFalse(readBareFile(fixture.remote, "local.txt") != null)
    }

    @Test
    fun nonFastForwardRejectionNeverForcesAndKeepsLocalWork() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("trabajo local")
        fixture.gateway.pushBehavior = { git, credentials ->
            commitAndPush(fixture.writer, "remote-race.txt", "carrera", "carrera durante push")
            fixture.gateway.delegate.pushMain(git, credentials)
        }

        val result = upload(fixture)

        assertEquals(UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertTrue(result.commitCreated)
        assertEquals(result.attemptedHead, head(fixture.local))
        assertNotEquals(head(fixture.local), bareHead(fixture.remote))
        assertEquals("carrera", readBareFile(fixture.remote, "remote-race.txt"))
        assertEquals(null, readBareFile(fixture.remote, "local.txt"))
    }

    @Test
    fun authenticationFailureRedactsTokenAndKeepsCreatedCommit() {
        val fixture = fixture()
        val secret = "token-super-secreto"
        File(fixture.local, "local.txt").writeText("trabajo local")
        fixture.gateway.pushBehavior = { _, _ ->
            PushTransportResult(PushTransportOutcome.AUTH_FAILED, secret)
        }
        val token = secret.toCharArray()

        val result = fixture.service.uploadSafely(fixture.local, token, "commit protegido")

        assertEquals(UploadOutcome.AUTH_FAILED, result.outcome)
        assertTrue(result.commitCreated)
        assertTrue(result.message.contains("guardado localmente"))
        assertFalse(result.toString().contains(secret))
        assertTrue(token.all { it == '\u0000' })
        assertEquals(result.attemptedHead, head(fixture.local))
        assertEquals(null, readBareFile(fixture.remote, "local.txt"))
    }

    @Test
    fun initialFetchFailureDoesNotStageCommitOrPush() {
        val fixture = fixture()
        fixture.gateway.failFetchOn = 1
        val localHead = head(fixture.local)
        File(fixture.local, "tracked.txt").writeText("conservar sin stage")

        val result = upload(fixture)

        assertEquals(UploadOutcome.FETCH_ERROR, result.outcome)
        assertEquals(0, fixture.gateway.pushCount)
        assertEquals(localHead, head(fixture.local))
        val currentStatus = status(fixture.local)
        assertEquals(setOf("tracked.txt"), currentStatus.modified)
        assertTrue(currentStatus.changed.isEmpty())
        assertEquals("conservar sin stage", File(fixture.local, "tracked.txt").readText())
    }

    @Test
    fun ambiguousResponseWithRemoteAtAttemptedHeadIsConfirmedOnce() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("subida aceptada")
        fixture.gateway.pushBehavior = { git, credentials ->
            val actual = fixture.gateway.delegate.pushMain(git, credentials)
            assertEquals(PushTransportOutcome.ACCEPTED, actual.outcome)
            PushTransportResult(PushTransportOutcome.AMBIGUOUS)
        }

        val result = upload(fixture)

        assertEquals(UploadOutcome.SUCCESS, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertEquals(3, fixture.gateway.fetchCount)
        assertEquals(result.attemptedHead, bareHead(fixture.remote))
        assertEquals(result.attemptedHead, originMain(fixture.local))
    }

    @Test
    fun acceptedPushRemainsSuccessIfRemoteAdvancesImmediatelyAfterwards() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("subida propia")
        fixture.gateway.beforeFetch = { count ->
            if (count == 3) {
                val actor = File(fixture.remote.parentFile, "actor")
                Git.cloneRepository()
                    .setURI(fixture.remote.toURI().toString())
                    .setBranch("main")
                    .setDirectory(actor)
                    .call()
                    .close()
                commitAndPush(actor, "actor.txt", "cambio posterior", "actor posterior")
            }
        }

        val result = upload(fixture)

        assertEquals(UploadOutcome.SUCCESS, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertNotEquals(result.attemptedHead, bareHead(fixture.remote))
        assertTrue(isBareAncestor(fixture.remote, requireNotNull(result.attemptedHead)))
        val finalState = requireNotNull(result.finalState)
        assertEquals(RepositoryStateType.REMOTE_AHEAD, finalState.type)
        assertTrue(finalState.behind > 0)
        assertTrue(result.message.contains("volvió a avanzar"))
    }

    @Test
    fun ambiguousResponseThatCannotBeConfirmedIsUncertainWithoutRetry() {
        val fixture = fixture()
        File(fixture.local, "local.txt").writeText("trabajo conservado")
        fixture.gateway.pushBehavior = { _, _ ->
            PushTransportResult(PushTransportOutcome.AMBIGUOUS)
        }
        fixture.gateway.failFetchOn = 3
        val originalRemote = bareHead(fixture.remote)

        val result = upload(fixture)

        assertEquals(UploadOutcome.PUSH_UNCERTAIN, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertEquals(3, fixture.gateway.fetchCount)
        assertTrue(result.commitCreated)
        assertTrue(result.message.contains("guardado localmente"))
        assertEquals(result.attemptedHead, head(fixture.local))
        assertEquals(originalRemote, bareHead(fixture.remote))
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
            git.remoteAdd().setName("origin").setUri(URIish(remote.toURI().toString())).call()
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
        val gateway = RecordingGateway()
        return Fixture(
            remote = remote,
            writer = writer,
            local = local,
            gateway = gateway,
            service = RepositoryStateService(remote.toURI().toString(), gateway),
        )
    }

    private fun upload(fixture: Fixture, message: String = "Cambios desde RobGit"): UploadResult =
        fixture.service.uploadSafely(fixture.local, "token-local".toCharArray(), message)

    private fun assertSuccessfulUpload(
        fixture: Fixture,
        result: UploadResult,
        expectedCommitCount: Int,
    ) {
        assertEquals(result.error, UploadOutcome.SUCCESS, result.outcome)
        assertEquals(1, fixture.gateway.pushCount)
        assertEquals(result.attemptedHead, head(fixture.local))
        assertEquals(result.attemptedHead, bareHead(fixture.remote))
        assertEquals(result.attemptedHead, originMain(fixture.local))
        assertEquals(expectedCommitCount, commitCount(fixture.local))
        assertTrue(status(fixture.local).isClean)
        val state = requireNotNull(result.finalState)
        assertEquals(RepositoryStateType.SYNCHRONIZED, state.type)
        assertEquals(0, state.ahead)
        assertEquals(0, state.behind)
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
        Git.open(directory).use { git ->
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
    }

    private fun head(directory: File): String = Git.open(directory).use { git ->
        requireNotNull(git.repository.resolve(Constants.HEAD)).name
    }

    private fun originMain(directory: File): String = Git.open(directory).use { git ->
        requireNotNull(git.repository.resolve("refs/remotes/origin/main")).name
    }

    private fun bareHead(directory: File): String = bareRepository(directory) { repository ->
        requireNotNull(repository.resolve("refs/heads/main")).name
    }

    private fun readBareFile(directory: File, path: String): String? =
        bareRepository(directory) { repository ->
            val head = repository.resolve("refs/heads/main") ?: return@bareRepository null
            org.eclipse.jgit.revwalk.RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(head)
                TreeWalk.forPath(repository, path, commit.tree)?.use { entry ->
                    repository.open(entry.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                }
            }
        }

    private fun isBareAncestor(directory: File, ancestor: String): Boolean =
        bareRepository(directory) { repository ->
            val tip = requireNotNull(repository.resolve("refs/heads/main"))
            org.eclipse.jgit.revwalk.RevWalk(repository).use { walk ->
                walk.isMergedInto(
                    walk.parseCommit(ObjectId.fromString(ancestor)),
                    walk.parseCommit(tip),
                )
            }
        }

    private fun <T> bareRepository(directory: File, block: (org.eclipse.jgit.lib.Repository) -> T): T {
        val repository = FileRepositoryBuilder().setGitDir(directory).setBare().build()
        return repository.use(block)
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
        val gateway: RecordingGateway,
        val service: RepositoryStateService,
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
            if (fetchCount == failFetchOn) {
                throw IllegalStateException("fallo de red simulado")
            }
            delegate.fetch(git, credentials)
        }

        override fun pushMain(
            git: Git,
            credentials: CredentialsProvider,
        ): PushTransportResult {
            pushCount++
            return pushBehavior?.invoke(git, credentials) ?: delegate.pushMain(git, credentials)
        }
    }
}
