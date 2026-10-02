package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class RodoPromptTest {
    private val builder = AiPromptBuilder()

    @Test fun universalPersonalityIsVersionedAndIndependentOfRobGit() {
        assertEquals("1.0", RodoPersonality.VERSION)
        val personality = RodoPersonality.systemPrompt
        assertTrue(personality.contains("asistente personal de Roberto"))
        assertTrue(personality.contains("castellano de España"))
        assertTrue(personality.contains("6/10"))
        assertTrue(personality.contains("devolverle una pulla"))
        assertTrue(personality.contains("admítelo"))
        assertTrue(personality.contains("abandona"))
        assertTrue(personality.contains("Jamás sacrifiques exactitud o seguridad"))
        assertFalse(personality.contains("RobGit"))
        assertFalse(personality.contains("Git"))
    }

    @Test fun modelInstructionComposesPersonalityThenAppContextWithoutRedefiningIt() {
        val instruction = builder.systemInstruction()
        assertTrue(instruction.indexOf(RodoPersonality.systemPrompt) <
            instruction.indexOf(RodoRobGitContext.systemPrompt))
        assertTrue(instruction.contains("versión 1.0"))
        assertTrue(instruction.contains("fuente factual"))
        assertFalse(RodoRobGitContext.systemPrompt.contains("6/10"))
        assertFalse(RodoRobGitContext.systemPrompt.contains("sarcástico"))
    }

    @Test fun appSafetyRulesRemainInSystemInstructionForEveryTask() {
        val instruction = builder.systemInstruction()
        for (rule in listOf("No puedes ejecutar ninguna operación Git", "No puedes modificar archivos",
            "No puedes realizar push, pull, merge, reset, force push ni borrar nada",
            "No inventes datos ausentes", "Solo analizas y explicas información",
            "No afirmes que una operación Git se ha ejecutado", "No alteres ni relativices")) {
            assertTrue("Missing $rule", instruction.contains(rule))
        }
        AiTask.entries.forEach { task ->
            val prompt = builder.build(AiRequest(task, AiTestFixtures.context(), "Detalle útil"))
            assertTrue(prompt.contains("TAREA:"))
            assertTrue(prompt.contains("CONTEXTO DEL REPOSITORIO:"))
            assertFalse(prompt.contains(RodoPersonality.systemPrompt))
            assertFalse(prompt.contains(AiTestFixtures.repository.remoteUrl))
        }
    }

    @Test fun currentRepositoryFactsAndRobertosQuestionReachTheUserMessage() {
        val prompt = builder.build(AiRequest(AiTask.CUSTOM_QUESTION, AiTestFixtures.context(),
            "¿Qué cambios tengo pendientes?"))
        assertTrue(prompt.contains("Nombre: Repositorio de prueba"))
        assertTrue(prompt.contains("Rama: main"))
        assertTrue(prompt.contains("Commits locales pendientes: 2"))
        assertTrue(prompt.contains("Modificados (1):\n- modified.txt"))
        assertTrue(prompt.contains("PETICIÓN DEL USUARIO:\n¿Qué cambios tengo pendientes?"))
    }
}
