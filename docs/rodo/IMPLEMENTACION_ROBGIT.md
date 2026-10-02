# Integración de la personalidad de Rodo en RobGit

`RODO_PERSONALITY_v1.0.md` es la especificación humana y canónica de la identidad transversal de Rodo. No se carga como un Markdown completo en cada consulta. `RodoPersonality.kt` contiene su versión de producción (`VERSION = "1.0"`), independiente de cualquier aplicación.

`RodoRobGitContext.kt` añade las capacidades, fuentes factuales y límites de seguridad propios de RobGit, sin redefinir la personalidad. `AiPromptBuilder.systemInstruction()` compone ambas capas en ese orden; `FirebaseGeminiAiProvider` la entrega mediante el parámetro real `systemInstruction` de Firebase AI Logic 17.17.0. El modelo y el backend siguen siendo los actuales.

Cada llamada a `generateContent` envía por separado la tarea, el contexto factual filtrado por `AiAssistantService` y la petición de Roberto. La identidad controla el tono, mientras que los hechos de RobGit y sus restricciones de seguridad controlan qué se puede afirmar o recomendar. No se han añadido acciones Git a Rodo.
