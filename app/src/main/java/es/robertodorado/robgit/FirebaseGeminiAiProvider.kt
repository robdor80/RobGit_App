package es.robertodorado.robgit

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content

internal class FirebaseGeminiAiProvider(
    private val promptBuilder: AiPromptBuilder = AiPromptBuilder(),
) : AiProvider {

    private val model by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = MODEL_NAME,
            systemInstruction = content { text(promptBuilder.systemInstruction()) },
        )
    }

    override suspend fun complete(request: AiRequest): AiResponse {
        val prompt = promptBuilder.build(request)

        val response = model.generateContent(prompt)

        val text = response.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Gemini devolvió una respuesta vacía.")

        return AiResponse(
            text = text,
            source = AiResponseSource.PROVIDER,
        )
    }

    companion object {
        private const val MODEL_NAME = "gemini-3.5-flash-lite"
    }
}
