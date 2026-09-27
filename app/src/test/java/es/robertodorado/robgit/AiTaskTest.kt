package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class AiTaskTest {
    @Test fun presetsMapExactlyToInformationalTasks() {
        assertEquals(listOf(
            "EXPLICAR ESTADO", "RESUMIR CAMBIOS", "PROPONER MENSAJE DE COMMIT",
            "EXPLICAR BLOQUEO O CONFLICTO", "PREGUNTA PERSONALIZADA",
        ), AiTask.entries.map { it.label })
        assertEquals(listOf("EXPLAIN_STATUS", "SUMMARIZE_CHANGES", "SUGGEST_COMMIT_MESSAGE",
            "EXPLAIN_BLOCK", "CUSTOM_QUESTION"), AiTask.entries.map { it.name })
    }

    @Test fun onlyCustomQuestionAllowsMissingAnalysis() {
        assertEquals(listOf(AiTask.CUSTOM_QUESTION), AiTask.entries.filterNot { it.requiresAnalyzedState })
    }
}
