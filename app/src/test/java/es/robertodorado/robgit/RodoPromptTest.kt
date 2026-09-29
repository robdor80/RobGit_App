package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class RodoPromptTest {
    @Test fun rodoIdentityIsPersonalWithoutMandatoryRepeatedIntroduction() {
        val prompt = AiPromptBuilder().build(AiRequest(AiTask.EXPLAIN_STATUS, AiTestFixtures.context()))
        assertTrue(prompt.contains("Eres Rodo, el asistente personal de Roberto"))
        assertTrue(prompt.contains("integrado en RobGit"))
        assertTrue(prompt.contains("castellano de España"))
        assertTrue(prompt.contains("Puedes dirigirte a Roberto por su nombre cuando resulte natural"))
        assertTrue(prompt.contains("sin repetir una presentación artificial"))
        assertFalse(prompt.contains("Eres el asistente integrado de RobGit"))
    }

    @Test fun promptKeepsInformationOnlyPrivacyAndDestructiveGitRestrictionsForEveryTask() {
        AiTask.entries.forEach { task ->
            val prompt = AiPromptBuilder().build(AiRequest(task, AiTestFixtures.context()))
            for (rule in listOf("No puedes ejecutar ninguna operación Git", "No puedes modificar archivos",
                "No puedes realizar push, pull, merge, reset, force push ni borrar nada",
                "No inventes datos ausentes", "Solo analizas y explicas información")) {
                assertTrue("$task missing $rule", prompt.contains(rule))
            }
            assertFalse(prompt.contains(AiTestFixtures.repository.remoteUrl))
        }
    }
}
