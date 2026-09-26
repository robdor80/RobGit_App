package es.robertodorado.robgit

internal fun repositorySelectionNeedsReset(previousId: String?, nextId: String?): Boolean =
    previousId != nextId

internal fun repositorySelectorEnabled(runningOperation: String?): Boolean =
    runningOperation == null
