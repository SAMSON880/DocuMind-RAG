package com.example.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ChunkEntity
import com.example.domain.ai.ChatbotPersona
import com.example.domain.ai.GeminiModelOption
import com.example.domain.ai.GeminiService
import com.example.domain.ai.WebSearchCitation
import com.example.domain.rag.LocalVectorEngine
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiViewModel"

data class GeminiChatMessage(
    val id: Long = System.nanoTime(),
    val role: String, // "user" or "model"
    val text: String,
    val modelLabel: String = "",
    val webSearchQueries: List<String> = emptyList(),
    val webCitations: List<WebSearchCitation> = emptyList(),
    val localPdfSources: List<String> = emptyList(),
    val isStreaming: Boolean = false
)

sealed interface GeminiChatUiState {
    data object Idle : GeminiChatUiState
    data object Loading : GeminiChatUiState
    data class Streaming(val partialText: String) : GeminiChatUiState
    data class Success(val text: String) : GeminiChatUiState
    data class Error(val message: String) : GeminiChatUiState
}

data class GeneratedImageEntry(
    val id: Long = System.nanoTime(),
    val bitmap: Bitmap,
    val prompt: String,
    val isEdited: Boolean
)

sealed interface ImageGenUiState {
    data object Idle : ImageGenUiState
    data class Loading(val modeLabel: String) : ImageGenUiState
    data class Success(val bitmap: Bitmap, val prompt: String, val isEdited: Boolean) : ImageGenUiState
    data class Error(val message: String) : ImageGenUiState
}

class GeminiViewModel : ViewModel() {

    private val geminiService by lazy { GeminiService() }

    // Multi-turn Chat State
    private val _selectedModel = MutableStateFlow(GeminiModelOption.FLASH)
    val selectedModel: StateFlow<GeminiModelOption> = _selectedModel.asStateFlow()

    private val _selectedPersona = MutableStateFlow(ChatbotPersona.RESEARCH_ANALYST)
    val selectedPersona: StateFlow<ChatbotPersona> = _selectedPersona.asStateFlow()

    private val _useGoogleSearch = MutableStateFlow(true)
    val useGoogleSearch: StateFlow<Boolean> = _useGoogleSearch.asStateFlow()

    private val _includeLocalPdfContext = MutableStateFlow(true)
    val includeLocalPdfContext: StateFlow<Boolean> = _includeLocalPdfContext.asStateFlow()

    private val _messages = MutableStateFlow<List<GeminiChatMessage>>(emptyList())
    val messages: StateFlow<List<GeminiChatMessage>> = _messages.asStateFlow()

    private val _chatUiState = MutableStateFlow<GeminiChatUiState>(GeminiChatUiState.Idle)
    val chatUiState: StateFlow<GeminiChatUiState> = _chatUiState.asStateFlow()

    // Image Studio State (gemini-nano-banana-2.1)
    private val _imageUiState = MutableStateFlow<ImageGenUiState>(ImageGenUiState.Idle)
    val imageUiState: StateFlow<ImageGenUiState> = _imageUiState.asStateFlow()

    private val _selectedSourceBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedSourceBitmap: StateFlow<Bitmap?> = _selectedSourceBitmap.asStateFlow()

    private val _imageHistory = MutableStateFlow<List<GeneratedImageEntry>>(emptyList())
    val imageHistory: StateFlow<List<GeneratedImageEntry>> = _imageHistory.asStateFlow()

    fun selectModel(option: GeminiModelOption) {
        _selectedModel.value = option
    }

    fun selectPersona(persona: ChatbotPersona) {
        _selectedPersona.value = persona
    }

    fun toggleGoogleSearch(enabled: Boolean) {
        _useGoogleSearch.value = enabled
    }

    fun toggleIncludeLocalPdfContext(enabled: Boolean) {
        _includeLocalPdfContext.value = enabled
    }

    fun clearGeminiChat() {
        _messages.value = emptyList()
        _chatUiState.value = GeminiChatUiState.Idle
    }

    fun sendChatMessage(
        userText: String,
        indexedChunks: List<ChunkEntity>
    ) {
        val cleanInput = userText.trim()
        if (cleanInput.isEmpty() || _chatUiState.value is GeminiChatUiState.Loading || _chatUiState.value is GeminiChatUiState.Streaming) {
            return
        }

        val previousHistory: List<Content> = _messages.value
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .map { msg ->
                content(role = if (msg.role == "user") "user" else "model") {
                    text(msg.text)
                }
            }

        // Retrieve local PDF chunks if enabled
        val matchedLocalChunks = if (_includeLocalPdfContext.value && indexedChunks.isNotEmpty()) {
            LocalVectorEngine.searchTopK(
                query = cleanInput,
                chunks = indexedChunks,
                topK = 3,
                similarityThreshold = 0.22f
            )
        } else {
            emptyList()
        }

        val localSourceBadges = matchedLocalChunks.map { "${it.documentName} (p.${it.pageNumber})" }.distinct()

        val effectivePrompt = if (matchedLocalChunks.isNotEmpty()) {
            val ctx = matchedLocalChunks.joinToString("\n\n") { c ->
                "[Local PDF: ${c.documentName}, Page ${c.pageNumber}]\n${c.text}"
            }
            "LOCAL UPLOADED PDF CONTEXT:\n$ctx\n\nUSER MESSAGE:\n$cleanInput"
        } else {
            cleanInput
        }

        // Append user message to thread
        val userMsg = GeminiChatMessage(
            role = "user",
            text = cleanInput,
            localPdfSources = localSourceBadges
        )
        _messages.value = _messages.value + userMsg

        val modelOpt = _selectedModel.value
        val personaOpt = _selectedPersona.value
        val searchEnabled = _useGoogleSearch.value

        viewModelScope.launch {
            _chatUiState.value = GeminiChatUiState.Loading
            try {
                if (searchEnabled) {
                    // Unary call with Google Search Grounding tool to capture GroundingMetadata citations
                    val result = withTimeout(60_000L) {
                        geminiService.sendMultiTurnMessage(
                            historyContents = previousHistory,
                            userPrompt = effectivePrompt,
                            modelOption = modelOpt,
                            persona = personaOpt,
                            useGoogleSearch = true
                        )
                    }

                    if (result.text.isNotBlank()) {
                        val modelReply = GeminiChatMessage(
                            role = "model",
                            text = result.text,
                            modelLabel = result.modelUsed,
                            webSearchQueries = result.webSearchQueries,
                            webCitations = result.webCitations,
                            localPdfSources = localSourceBadges
                        )
                        _messages.value = _messages.value + modelReply
                        _chatUiState.value = GeminiChatUiState.Success(result.text)
                    } else {
                        _chatUiState.value = GeminiChatUiState.Error("Gemini returned an empty response.")
                    }
                } else {
                    // Streaming multi-turn response for low Time-To-First-Token (TTFT)
                    val streamingId = System.nanoTime()
                    val placeholderMsg = GeminiChatMessage(
                        id = streamingId,
                        role = "model",
                        text = "",
                        modelLabel = modelOpt.modelId,
                        localPdfSources = localSourceBadges,
                        isStreaming = true
                    )
                    _messages.value = _messages.value + placeholderMsg

                    val accumulated = StringBuilder()
                    withTimeout(60_000L) {
                        geminiService.streamMultiTurnMessage(
                            historyContents = previousHistory,
                            userPrompt = effectivePrompt,
                            modelOption = modelOpt,
                            persona = personaOpt
                        ).collect { token ->
                            accumulated.append(token)
                            val currentText = accumulated.toString()
                            _chatUiState.value = GeminiChatUiState.Streaming(currentText)
                            _messages.value = _messages.value.map { m ->
                                if (m.id == streamingId) m.copy(text = currentText) else m
                            }
                        }
                    }

                    val finalText = accumulated.toString().trim()
                    if (finalText.isNotBlank()) {
                        _messages.value = _messages.value.map { m ->
                            if (m.id == streamingId) m.copy(text = finalText, isStreaming = false) else m
                        }
                        _chatUiState.value = GeminiChatUiState.Success(finalText)
                    } else {
                        _messages.value = _messages.value.filterNot { it.id == streamingId }
                        _chatUiState.value = GeminiChatUiState.Error("Model returned an empty response.")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini chat timed out", e)
                _messages.value = _messages.value.filterNot { it.isStreaming && it.text.isBlank() }
                _chatUiState.value = GeminiChatUiState.Error(
                    "Generation timed out after 60 seconds. Please try again."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini chat generation failed", e)
                _messages.value = _messages.value.filterNot { it.isStreaming && it.text.isBlank() }
                _chatUiState.value = GeminiChatUiState.Error(
                    e.localizedMessage ?: "Gemini request failed."
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Image Studio (gemini-nano-banana-2.1) Create & Edit
    // -------------------------------------------------------------------------
    fun setSourceBitmapForEditing(bitmap: Bitmap?) {
        _selectedSourceBitmap.value = bitmap
    }

    fun generateNewImage(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() || _imageUiState.value is ImageGenUiState.Loading) return

        viewModelScope.launch {
            _imageUiState.value = ImageGenUiState.Loading("Synthesizing image with gemini-nano-banana-2.1 (may take 30–60s)…")
            try {
                val bitmap = withTimeout(60_000L) {
                    geminiService.generateImageFromPrompt(cleanPrompt)
                }
                if (bitmap != null) {
                    _selectedSourceBitmap.value = bitmap
                    val entry = GeneratedImageEntry(
                        bitmap = bitmap,
                        prompt = cleanPrompt,
                        isEdited = false
                    )
                    _imageHistory.value = listOf(entry) + _imageHistory.value
                    _imageUiState.value = ImageGenUiState.Success(
                        bitmap = bitmap,
                        prompt = cleanPrompt,
                        isEdited = false
                    )
                } else {
                    _imageUiState.value = ImageGenUiState.Error("No image was returned by gemini-nano-banana-2.1.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Image generation timed out", e)
                _imageUiState.value = ImageGenUiState.Error("Image generation timed out after 60 seconds.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image generation failed", e)
                _imageUiState.value = ImageGenUiState.Error(
                    e.localizedMessage ?: "Image generation failed."
                )
            }
        }
    }

    fun editExistingImage(inputBitmap: Bitmap, editInstruction: String) {
        val cleanInstruction = editInstruction.trim()
        if (cleanInstruction.isEmpty() || _imageUiState.value is ImageGenUiState.Loading) return

        viewModelScope.launch {
            _imageUiState.value = ImageGenUiState.Loading("Editing image with gemini-nano-banana-2.1 (may take 30–60s)…")
            try {
                val editedBitmap = withTimeout(60_000L) {
                    geminiService.editImageWithPrompt(inputBitmap, cleanInstruction)
                }
                if (editedBitmap != null) {
                    _selectedSourceBitmap.value = editedBitmap
                    val entry = GeneratedImageEntry(
                        bitmap = editedBitmap,
                        prompt = "Edit: $cleanInstruction",
                        isEdited = true
                    )
                    _imageHistory.value = listOf(entry) + _imageHistory.value
                    _imageUiState.value = ImageGenUiState.Success(
                        bitmap = editedBitmap,
                        prompt = cleanInstruction,
                        isEdited = true
                    )
                } else {
                    _imageUiState.value = ImageGenUiState.Error("No edited image was returned by gemini-nano-banana-2.1.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Image editing timed out", e)
                _imageUiState.value = ImageGenUiState.Error("Image editing timed out after 60 seconds.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image editing failed", e)
                _imageUiState.value = ImageGenUiState.Error(
                    e.localizedMessage ?: "Image editing failed."
                )
            }
        }
    }
}
