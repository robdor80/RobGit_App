package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.time.Instant
import java.util.Arrays
import java.util.UUID

private const val REMOTE_TEST_URL = "https://github.com/robdor80/RobGit_App.git"
private const val PUSH_TEST_URL = "https://github.com/robdor80/Robgit.pruebas.git"
private const val PUSH_TEST_USERNAME = "robdor80"
private const val PUSH_TEST_FILE = "robgit_android_test.txt"
private const val PUSH_TEST_MESSAGE = "Prueba de push desde RobGit Android"

data class DiagnosticStep(val description: String, val detail: String = "")

data class DiagnosticResult(
    val repositoryPath: String,
    val branch: String,
    val lastCommit: String,
    val status: String,
    val steps: List<DiagnosticStep>,
    val error: String? = null,
)

/** Only local Git operations needed to test JGit inside Android. */
class GitRepositoryService {
    fun runLocalDiagnostic(repositoryDirectory: File): DiagnosticResult {
        val steps = mutableListOf<DiagnosticStep>()
        var branch = "Sin consultar"
        var lastCommit = "Sin commits"
        var status = "Sin consultar"

        try {
            require(!repositoryDirectory.exists()) {
                "La carpeta de diagnóstico ya existe: ${repositoryDirectory.absolutePath}"
            }

            Git.init()
                .setDirectory(repositoryDirectory)
                .setInitialBranch("main")
                .call().use { git ->
                    steps += DiagnosticStep("Repositorio inicializado")

                    branch = git.repository.branch
                    check(branch == "main") { "Rama inesperada: $branch" }
                    check(git.repository.resolve(Constants.HEAD) == null) {
                        "El repositorio nuevo ya contiene un commit"
                    }
                    steps += DiagnosticStep("Rama y HEAD consultados", "$branch; sin commits")

                    val filename = "diagnostico.txt"
                    val contents = "Prueba local de JGit en Android\n"
                    val testFile = File(repositoryDirectory, filename)
                    testFile.writeText(contents, Charsets.UTF_8)
                    check(testFile.readText(Charsets.UTF_8) == contents)
                    steps += DiagnosticStep("Archivo creado", filename)

                    val beforeAdd = git.status().call()
                    check(filename in beforeAdd.untracked) {
                        "JGit no detectó el archivo nuevo: ${beforeAdd.untracked}"
                    }
                    status = "Archivo nuevo detectado: $filename"
                    steps += DiagnosticStep("Cambio detectado", status)

                    git.add().addFilepattern(filename).call()
                    val afterAdd = git.status().call()
                    check(filename in afterAdd.added) {
                        "JGit no detectó el archivo en el índice: ${afterAdd.added}"
                    }
                    steps += DiagnosticStep("Archivo añadido al índice", filename)

                    val commit = git.commit()
                        .setMessage("Prueba local de JGit")
                        .setAuthor("RobGit Spike", "spike@localhost")
                        .setCommitter("RobGit Spike", "spike@localhost")
                        .call()
                    check(git.repository.resolve(Constants.HEAD) == commit.id) {
                        "HEAD no apunta al commit creado"
                    }
                    steps += DiagnosticStep("Commit creado", commit.id.abbreviate(7).name())

                    val finalStatus = git.status().call()
                    check(finalStatus.isClean) { "El working tree no está limpio: $finalStatus" }
                    status = "Working tree limpio"
                    steps += DiagnosticStep("Estado final consultado", status)

                    val latest = git.log().setMaxCount(1).call().firstOrNull()
                    check(latest?.id == commit.id) { "No se pudo leer el último commit" }
                    lastCommit = "${latest.id.abbreviate(7).name()} — ${latest.shortMessage}"
                    steps += DiagnosticStep("Último commit leído", lastCommit)
                }
        } catch (failure: Throwable) {
            // A missing Java API on Android can surface as a LinkageError.
            if (failure !is Exception && failure !is LinkageError) throw failure
            val detail = generateSequence(failure) { it.cause }
                .take(3)
                .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message ?: "sin detalle"}" }
            return DiagnosticResult(
                repositoryPath = repositoryDirectory.absolutePath,
                branch = branch,
                lastCommit = lastCommit,
                status = status,
                steps = steps,
                error = detail,
            )
        }

        return DiagnosticResult(
            repositoryPath = repositoryDirectory.absolutePath,
            branch = branch,
            lastCommit = lastCommit,
            status = status,
            steps = steps,
        )
    }

    fun runRemoteCloneDiagnostic(repositoryDirectory: File): DiagnosticResult {
        val steps = mutableListOf<DiagnosticStep>()
        var stage = "Preparando el clone HTTPS"
        var branch = "Sin consultar"
        var lastCommit = "Sin leer"
        var status = "Sin consultar"

        try {
            require(!repositoryDirectory.exists()) {
                "La carpeta de diagnóstico ya existe: ${repositoryDirectory.absolutePath}"
            }

            Git.cloneRepository()
                .setURI(REMOTE_TEST_URL)
                .setDirectory(repositoryDirectory)
                .setCredentialsProvider(null)
                .setTimeout(60)
                .call().use {
                    steps += DiagnosticStep("Conexión HTTPS", "Clone anónimo completado")
                }

            stage = "Abriendo y validando el repositorio clonado"
            check(File(repositoryDirectory, ".git").isDirectory) {
                "No existe el directorio .git del clone"
            }
            Git.open(repositoryDirectory).use { git ->
                check(!git.repository.isBare && git.repository.objectDatabase.exists()) {
                    "El directorio clonado no es un repositorio Git válido"
                }
                steps += DiagnosticStep("Repositorio clonado", "Repositorio Git válido")

                stage = "Validando HEAD y rama"
                val head = git.repository.resolve(Constants.HEAD)
                check(head != null) { "El repositorio no tiene HEAD" }
                branch = git.repository.branch
                check(branch == "main") { "Se esperaba la rama main, se obtuvo $branch" }
                steps += DiagnosticStep("Rama activa", branch)

                stage = "Validando remoto origin"
                val originUrl = git.repository.config.getString("remote", "origin", "url")
                check(originUrl == REMOTE_TEST_URL) {
                    "URL de origin inesperada: ${originUrl ?: "ausente"}"
                }
                steps += DiagnosticStep("origin configurado", originUrl)

                stage = "Validando refs/remotes/origin/main"
                val remoteMain = git.repository.exactRef("refs/remotes/origin/main")
                check(remoteMain != null) { "No existe refs/remotes/origin/main" }
                steps += DiagnosticStep("Rama remota encontrada", "origin/main")

                stage = "Comparando HEAD con origin/main"
                check(head == remoteMain.objectId) {
                    "HEAD ($head) no coincide con origin/main (${remoteMain.objectId})"
                }
                steps += DiagnosticStep("HEAD coincide con origin/main", head.abbreviate(7).name())

                stage = "Comprobando el working tree"
                val finalStatus = git.status().call()
                check(finalStatus.isClean) { "El working tree no está limpio: $finalStatus" }
                status = "Working tree limpio"
                steps += DiagnosticStep("Working tree limpio")

                stage = "Comprobando README.md"
                val readme = File(repositoryDirectory, "README.md")
                check(readme.isFile && readme.length() > 0L) {
                    "README.md no existe o está vacío"
                }
                readme.inputStream().use { stream ->
                    val firstByte = stream.read()
                    check(firstByte >= 0) { "README.md no se pudo leer" }
                }
                steps += DiagnosticStep("README.md encontrado y legible", "${readme.length()} bytes")

                stage = "Leyendo el último commit"
                val latest = git.log().setMaxCount(1).call().firstOrNull()
                check(latest != null && latest.id == head) {
                    "No se pudo leer el commit apuntado por HEAD"
                }
                lastCommit = "${latest.id.abbreviate(7).name()} — ${latest.shortMessage}"
                steps += DiagnosticStep("Último commit leído", lastCommit)
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            val causes = generateSequence(failure) { it.cause }
                .take(5)
                .joinToString(" ← ") {
                    "${it.javaClass.name}: ${it.message ?: "sin detalle"}"
                }
            return DiagnosticResult(
                repositoryPath = repositoryDirectory.absolutePath,
                branch = branch,
                lastCommit = lastCommit,
                status = status,
                steps = steps,
                error = "$stage — $causes",
            )
        }

        return DiagnosticResult(
            repositoryPath = repositoryDirectory.absolutePath,
            branch = branch,
            lastCommit = lastCommit,
            status = status,
            steps = steps,
        )
    }

    fun runAuthenticatedPushDiagnostic(
        repositoryDirectory: File,
        token: CharArray,
    ): DiagnosticResult {
        val steps = mutableListOf<DiagnosticStep>()
        var stage = "Preparando el clone autenticado"
        var branch = "Sin consultar"
        var lastCommit = "Sin leer"
        var status = "Sin iniciar"
        var localCommit: ObjectId? = null
        var pushStarted = false
        var pushResponseReceived = false
        var pushAcceptedByResponse = false
        var remoteConfirmed = false
        val credentials = UsernamePasswordCredentialsProvider(PUSH_TEST_USERNAME, token)

        try {
            require(token.isNotEmpty()) { "Introduce un token antes de ejecutar la prueba" }
            require(!repositoryDirectory.exists()) {
                "La carpeta de diagnóstico ya existe: ${repositoryDirectory.absolutePath}"
            }

            val git = Git.cloneRepository()
                .setURI(PUSH_TEST_URL)
                .setDirectory(repositoryDirectory)
                .setCredentialsProvider(credentials)
                .setTimeout(60)
                .call()

            git.use {
                stage = "Validando el repositorio clonado"
                check(File(repositoryDirectory, ".git").isDirectory) {
                    "No existe el directorio .git del clone"
                }
                check(!git.repository.isBare && git.repository.objectDatabase.exists()) {
                    "El directorio clonado no es un repositorio Git válido"
                }
                branch = git.repository.branch
                check(branch == "main") { "Se esperaba la rama main, se obtuvo $branch" }
                val cloneHead = git.repository.resolve(Constants.HEAD)
                check(cloneHead != null) { "El repositorio clonado no tiene HEAD" }
                val originUrl = git.repository.config.getString("remote", "origin", "url")
                check(originUrl == PUSH_TEST_URL) {
                    "URL de origin inesperada: ${originUrl ?: "ausente"}"
                }
                val startingRemoteCommit = git.repository
                    .exactRef("refs/remotes/origin/main")?.objectId
                check(startingRemoteCommit != null && startingRemoteCommit == cloneHead) {
                    "origin/main no coincide con HEAD después del clone"
                }
                check(git.status().call().isClean) { "El working tree no está limpio tras el clone" }
                steps += DiagnosticStep("Repositorio de pruebas clonado", cloneHead.abbreviate(7).name())

                stage = "Creando el archivo exclusivo de diagnóstico"
                val runId = "${Instant.now()} ${UUID.randomUUID()}"
                val testFile = File(repositoryDirectory, PUSH_TEST_FILE)
                testFile.writeText("Prueba RobGit Android\nEjecución: $runId\n", Charsets.UTF_8)
                check(testFile.isFile && testFile.length() > 0L)
                steps += DiagnosticStep("Archivo de prueba creado", PUSH_TEST_FILE)

                stage = "Comprobando el cambio local"
                val changedStatus = git.status().call()
                check(
                    PUSH_TEST_FILE in changedStatus.untracked ||
                        PUSH_TEST_FILE in changedStatus.modified ||
                        PUSH_TEST_FILE in changedStatus.changed
                ) { "JGit no detectó el cambio en $PUSH_TEST_FILE" }
                steps += DiagnosticStep("Cambio detectado", PUSH_TEST_FILE)

                stage = "Añadiendo el archivo de diagnóstico al índice"
                git.add().addFilepattern(PUSH_TEST_FILE).call()
                val stagedStatus = git.status().call()
                check(
                    PUSH_TEST_FILE in stagedStatus.added ||
                        PUSH_TEST_FILE in stagedStatus.changed
                ) { "JGit no detectó $PUSH_TEST_FILE en el índice" }
                steps += DiagnosticStep("Archivo añadido", PUSH_TEST_FILE)

                stage = "Creando el commit local"
                val commit = git.commit()
                    .setMessage(PUSH_TEST_MESSAGE)
                    .setAuthor("RobGit Android Spike", "spike@localhost")
                    .setCommitter("RobGit Android Spike", "spike@localhost")
                    .call()
                localCommit = commit.id
                check(commit.id != cloneHead && git.repository.resolve(Constants.HEAD) == commit.id) {
                    "HEAD no cambió al nuevo commit"
                }
                lastCommit = "${commit.id.abbreviate(7).name()} — ${commit.shortMessage}"
                steps += DiagnosticStep("Commit local creado", commit.id.abbreviate(7).name())

                stage = "Comprobando GitHub antes del push"
                git.fetch()
                    .setRemote("origin")
                    .setCredentialsProvider(credentials)
                    .setTimeout(60)
                    .call()
                val remoteBeforePush = git.repository
                    .exactRef("refs/remotes/origin/main")?.objectId
                check(remoteBeforePush != null) { "No existe origin/main después del fetch" }
                if (remoteBeforePush != startingRemoteCommit) {
                    status = "Detenido con seguridad"
                    return DiagnosticResult(
                        repositoryPath = repositoryDirectory.absolutePath,
                        branch = branch,
                        lastCommit = lastCommit,
                        status = status,
                        steps = steps,
                        error = "GitHub ha cambiado desde que comenzó la prueba. No se realizará el push.",
                    )
                }
                steps += DiagnosticStep("Remoto comprobado antes del push", "Sin cambios desde el clone")

                stage = "Ejecutando el push normal de main"
                pushStarted = true
                val pushResults = git.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .setCredentialsProvider(credentials)
                    .setTimeout(60)
                    .call()
                pushResponseReceived = true
                val updates = pushResults.flatMap { it.remoteUpdates }
                check(updates.isNotEmpty()) { "GitHub no devolvió resultado para refs/heads/main" }
                val rejected = updates.firstOrNull { it.status != RemoteRefUpdate.Status.OK }
                check(rejected == null) {
                    "GitHub rechazó el push: ${rejected?.status} ${rejected?.message.orEmpty()}"
                }
                pushAcceptedByResponse = true
                steps += DiagnosticStep("Credenciales aceptadas")
                steps += DiagnosticStep("Push completado", commit.id.abbreviate(7).name())

                stage = "Verificando el commit directamente en GitHub"
                val remoteCommit = readRemoteMain(credentials)
                if (remoteCommit != commit.id) {
                    return DiagnosticResult(
                        repositoryPath = repositoryDirectory.absolutePath,
                        branch = branch,
                        lastCommit = lastCommit,
                        status = "Commit local creado; push no confirmado",
                        steps = steps,
                        error = "GitHub no apunta al commit local. No se repetirá automáticamente el push.",
                    )
                }
                remoteConfirmed = true
                steps += DiagnosticStep("GitHub apunta al commit", commit.id.abbreviate(7).name())

                stage = "Comprobando el working tree final"
                check(git.status().call().isClean) { "El working tree no está limpio después del push" }
                status = "Push confirmado remotamente; working tree limpio"
                steps += DiagnosticStep("Working tree limpio")
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            val safeError = authenticatedError(stage, failure, token)
            if (safeError == "GitHub rechazó las credenciales.") {
                return DiagnosticResult(
                    repositoryPath = repositoryDirectory.absolutePath,
                    branch = branch,
                    lastCommit = lastCommit,
                    status = status,
                    steps = steps,
                    error = safeError,
                )
            }
            if (pushStarted && !pushResponseReceived && localCommit != null) {
                return resolveUncertainPush(
                    repositoryDirectory = repositoryDirectory,
                    branch = branch,
                    lastCommit = lastCommit,
                    steps = steps,
                    localCommit = localCommit,
                    credentials = credentials,
                )
            }
            if (pushAcceptedByResponse && !remoteConfirmed) {
                return DiagnosticResult(
                    repositoryPath = repositoryDirectory.absolutePath,
                    branch = branch,
                    lastCommit = lastCommit,
                    status = "Commit local creado; resultado del push indeterminado",
                    steps = steps,
                    error = "No se ha podido confirmar si GitHub recibió el commit. No se repetirá automáticamente el push.",
                )
            }
            if (remoteConfirmed) {
                return DiagnosticResult(
                    repositoryPath = repositoryDirectory.absolutePath,
                    branch = branch,
                    lastCommit = lastCommit,
                    status = "Push confirmado remotamente",
                    steps = steps,
                    error = safeError,
                )
            }
            return DiagnosticResult(
                repositoryPath = repositoryDirectory.absolutePath,
                branch = branch,
                lastCommit = lastCommit,
                status = status,
                steps = steps,
                error = safeError,
            )
        } finally {
            credentials.clear()
            Arrays.fill(token, '\u0000')
        }

        return DiagnosticResult(
            repositoryPath = repositoryDirectory.absolutePath,
            branch = branch,
            lastCommit = lastCommit,
            status = status,
            steps = steps,
        )
    }

    private fun resolveUncertainPush(
        repositoryDirectory: File,
        branch: String,
        lastCommit: String,
        steps: MutableList<DiagnosticStep>,
        localCommit: ObjectId,
        credentials: UsernamePasswordCredentialsProvider,
    ): DiagnosticResult = try {
        val remoteCommit = readRemoteMain(credentials)
        if (remoteCommit == localCommit) {
            steps += DiagnosticStep(
                "Push confirmado pese al error de conexión",
                localCommit.abbreviate(7).name(),
            )
            DiagnosticResult(
                repositoryPath = repositoryDirectory.absolutePath,
                branch = branch,
                lastCommit = lastCommit,
                status = "Push confirmado remotamente pese al error de conexión",
                steps = steps,
            )
        } else {
            DiagnosticResult(
                repositoryPath = repositoryDirectory.absolutePath,
                branch = branch,
                lastCommit = lastCommit,
                status = "Commit local creado; push no confirmado",
                steps = steps,
                error = "GitHub no apunta al commit local. No se repetirá automáticamente el push.",
            )
        }
    } catch (_: Throwable) {
        DiagnosticResult(
            repositoryPath = repositoryDirectory.absolutePath,
            branch = branch,
            lastCommit = lastCommit,
            status = "Commit local creado; resultado del push indeterminado",
            steps = steps,
            error = "No se ha podido confirmar si GitHub recibió el commit. No se repetirá automáticamente el push.",
        )
    }

    private fun readRemoteMain(
        credentials: UsernamePasswordCredentialsProvider,
    ): ObjectId? = Git.lsRemoteRepository()
        .setRemote(PUSH_TEST_URL)
        .setHeads(true)
        .setTags(false)
        .setCredentialsProvider(credentials)
        .setTimeout(60)
        .call()
        .firstOrNull { it.name == "refs/heads/main" }
        ?.objectId

    internal fun authenticatedError(stage: String, failure: Throwable, token: CharArray): String {
        val raw = generateSequence(failure) { it.cause }
            .take(5)
            .joinToString(" ← ") { "${it.javaClass.name}: ${it.message ?: "sin detalle"}" }
        val tokenText = String(token)
        val redacted = if (tokenText.isEmpty()) raw else raw.replace(tokenText, "[credencial oculta]")
        val lower = redacted.lowercase()
        return if (
            "not authorized" in lower || "authentication" in lower ||
            "401" in lower || "403" in lower
        ) {
            "GitHub rechazó las credenciales."
        } else {
            "$stage — $redacted"
        }
    }
}
