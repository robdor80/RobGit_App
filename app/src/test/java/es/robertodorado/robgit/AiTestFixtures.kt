package es.robertodorado.robgit

import java.time.Instant

internal object AiTestFixtures {
    val repository = RepositoryConfig("id-not-for-ai", "Repositorio de prueba",
        "https://never-requested.invalid/private/repository.git", "main", "workspace-not-for-ai")

    fun state() = RepositoryStateSnapshot(
        RepositoryStateType.LOCAL_CHANGES, CommitRelation.LOCAL_AHEAD, "Hay cambios locales.",
        "main", "local-object-not-for-ai", "remote-object-not-for-ai", 2, 0,
        WorkingTreeChanges(
            newFiles = setOf("added.txt", "untracked.txt"),
            modifiedFiles = setOf("modified.txt"),
            deletedFiles = setOf("deleted.txt"),
            stagedFiles = setOf("added.txt", "modified.txt", "deleted.txt"),
        ),
        Instant.parse("2026-09-28T10:00:00Z"), true,
    )

    fun context() = AiContextBuilder().build(repository, state(), RepositoryStatusPresenter.present(state()),
        AiLastOperation("ANALIZAR AHORA", "LOCAL_CHANGES", "Análisis manual completado."))
}
