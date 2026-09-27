package es.robertodorado.robgit

/** Resolves or refreshes OAuth before the PUSH commit prompt can be shown. */
internal suspend fun hasPushAuthentication(access: GitHubAccessTokenProvider): Boolean =
    access.validAccessToken() != null
