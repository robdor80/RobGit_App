package es.robertodorado.robgit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositorySelectionPolicyTest {
    @Test fun switchingRepositoriesInvalidatesPreviouslyDisplayedState() {
        assertTrue(repositorySelectionNeedsReset("repo-a", "repo-b"))
        assertTrue(repositorySelectionNeedsReset("repo-a", null))
        assertTrue(repositorySelectionNeedsReset(null, "repo-a"))
    }

    @Test fun reopeningOrRotatingOnSameSelectionRetainsDisplayedState() {
        assertFalse(repositorySelectionNeedsReset("repo-a", "repo-a"))
        assertFalse(repositorySelectionNeedsReset(null, null))
    }

    @Test fun selectorIsLockedDuringEveryOperation() {
        assertTrue(repositorySelectorEnabled(null))
        listOf("analizando", "preparando", "PULL", "PUSH", "SINCRONIZAR", "añadiendo", "quitando")
            .forEach { assertFalse(repositorySelectorEnabled(it)) }
    }
}
