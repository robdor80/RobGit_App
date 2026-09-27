package es.robertodorado.robgit

import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/** JGit's expected username for GitHub HTTPS OAuth user access tokens. */
internal fun githubJGitCredentials(token: CharArray): UsernamePasswordCredentialsProvider =
    UsernamePasswordCredentialsProvider("x-access-token", token)
