package es.robertodorado.robgit

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Test

class GitHubJGitCredentialsTest {
    @Test fun oauthTokenBecomesGithubHttpsUsernameAndPassword() {
        val temporary = "oauth-test-value".toCharArray()
        val provider = githubJGitCredentials(temporary)
        try {
            val username = CredentialItem.Username()
            val password = CredentialItem.Password()
            assertTrue(provider.get(URIish("https://github.com/example/project.git"), username, password))
            assertEquals("x-access-token", username.value)
            assertArrayEquals(temporary, password.value)
        } finally {
            provider.clear()
            temporary.fill('\u0000')
        }
    }
}
