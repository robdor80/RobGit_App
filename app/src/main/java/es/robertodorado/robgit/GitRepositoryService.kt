package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.io.File

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
}
