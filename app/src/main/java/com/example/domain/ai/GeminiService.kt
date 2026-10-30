package com.example.domain.ai

import android.graphics.Bitmap
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class GeminiModelOption(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val description: String
) {
    FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        badge = "General + Search",
        description = "Default frontier model for general chat & Google Search grounding"
    ),
    FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        badge = "Ultra-Fast",
        description = "Low-latency workhorse for rapid multi-turn responses"
    ),
    ARGON(
        modelId = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        badge = "Deep Reasoning",
        description = "Complex problem-solving, architecture, math & STEM analysis"
    )
}

enum class ChatbotPersona(
    val title: String,
    val systemInstructionText: String
) {
    RESEARCH_ANALYST(
        title = "Document & Web Analyst",
        systemInstructionText = "You are DocuMind's Senior Research Analyst. Synthesize clear, accurate, well-structured answers. When local PDF context or Google Search grounding is provided, cite specific facts and sources clearly."
    ),
    AI_ENGINEER_MENTOR(
        title = "AI Engineering Mentor",
        systemInstructionText = "You are a Principal AI Systems Engineer mentoring a developer on Retrieval-Augmented Generation (RAG), embeddings, vector databases, and LLM architecture. Explain concepts with concrete examples and clean code."
    ),
    EXECUTIVE_BRIEF(
        title = "Executive Summarizer",
        systemInstructionText = "You are an Executive Briefing Assistant. Deliver concise, high-signal bullet points, key takeaways, and action items without fluff."
    )
}

data class WebSearchCitation(
    val title: String,
    val domain: String,
    val uri: String
)

data class GeminiChatTurnResult(
    val text: String,
    val webSearchQueries: List<String> = emptyList(),
    val webCitations: List<WebSearchCitation> = emptyList(),
    val modelUsed: String
)

/**
 * Wraps Firebase AI Logic (`Firebase.ai`) for:
 * 1. Multi-turn Gemini Chat with custom `systemInstruction` (`gemini-3.8-flash`, `gemini-3.5-flash-lite`, `gemini-4-argon`).
 * 2. Live Google Search Grounding (`gemini-3.8-flash` with `Tool.googleSearch()`).
 * 3. Runtime Image Creation & Image Editing (`gemini-nano-banana-2.1`).
 *
 * All models are initialized lazily on demand so `Firebase.ai` is never called eagerly at construction time.
 */
class GeminiService {

    private val imageGenModel: GenerativeModel by lazy {
        val config = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-nano-banana-2.1",
            generationConfig = config
        )
    }

    private fun createChatModel(
        modelOption: GeminiModelOption,
        persona: ChatbotPersona,
        useGoogleSearch: Boolean
    ): GenerativeModel {
        // Google Search grounding uses gemini-3.8-flash with Tool.googleSearch()
        val effectiveModelName = if (useGoogleSearch) {
            GeminiModelOption.FLASH.modelId
        } else {
            modelOption.modelId
        }

        val toolsList = if (useGoogleSearch) {
            listOf(Tool.googleSearch())
        } else {
            emptyList()
        }

        return Firebase.ai.generativeModel(
            modelName = effectiveModelName,
            systemInstruction = content { text(persona.systemInstructionText) },
            tools = toolsList
        )
    }

    /**
     * Executes a multi-turn chat message (with optional Google Search grounding and optional
     * local PDF RAG context) and extracts any Google Search grounding metadata.
     */
    suspend fun sendMultiTurnMessage(
        historyContents: List<Content>,
        userPrompt: String,
        modelOption: GeminiModelOption,
        persona: ChatbotPersona,
        useGoogleSearch: Boolean
    ): GeminiChatTurnResult {
        val model = createChatModel(modelOption, persona, useGoogleSearch)
        val chat = model.startChat(history = historyContents)
        val response = chat.sendMessage(userPrompt)

        val text = response.text?.trim().orEmpty()
        val candidate = response.candidates.firstOrNull()
        val groundingMeta = candidate?.groundingMetadata

        val searchQueries = groundingMeta?.webSearchQueries?.filter { it.isNotBlank() }.orEmpty()
        val citations = groundingMeta?.groundingChunks
            ?.mapNotNull { chunk ->
                val web = chunk.web
                if (web != null && !web.uri.isNullOrBlank()) {
                    WebSearchCitation(
                        title = web.title?.takeIf { it.isNotBlank() } ?: web.domain ?: "Web Source",
                        domain = web.domain?.takeIf { it.isNotBlank() } ?: "",
                        uri = web.uri!!
                    )
                } else {
                    null
                }
            }
            ?.distinctBy { it.uri }
            .orEmpty()

        val usedModelLabel = if (useGoogleSearch) {
            "${GeminiModelOption.FLASH.modelId} + Google Search"
        } else {
            modelOption.modelId
        }

        return GeminiChatTurnResult(
            text = text,
            webSearchQueries = searchQueries,
            webCitations = citations,
            modelUsed = usedModelLabel
        )
    }

    /**
     * Streams tokens in real time for multi-turn chat when Google Search grounding metadata is not required.
     */
    fun streamMultiTurnMessage(
        historyContents: List<Content>,
        userPrompt: String,
        modelOption: GeminiModelOption,
        persona: ChatbotPersona
    ): Flow<String> {
        val model = createChatModel(
            modelOption = modelOption,
            persona = persona,
            useGoogleSearch = false
        )
        val chat = model.startChat(history = historyContents)
        return chat.sendMessageStream(userPrompt).map { chunk ->
            chunk.text.orEmpty()
        }
    }

    /**
     * Generates an image from a text prompt using `gemini-nano-banana-2.1`.
     */
    suspend fun generateImageFromPrompt(promptText: String): Bitmap? {
        val response = imageGenModel.generateContent(promptText)
        val imagePart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()
        return imagePart?.image
    }

    /**
     * Edits an existing input Bitmap using a text instruction with `gemini-nano-banana-2.1`.
     */
    suspend fun editImageWithPrompt(
        inputBitmap: Bitmap,
        editInstruction: String
    ): Bitmap? {
        val response = imageGenModel.generateContent(
            content {
                image(inputBitmap)
                text(editInstruction)
            }
        )
        val imagePart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()
        return imagePart?.image
    }
}
