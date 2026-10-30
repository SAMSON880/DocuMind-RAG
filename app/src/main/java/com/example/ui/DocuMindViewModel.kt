package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatMessageItem
import com.example.data.DocuMindRepository
import com.example.data.local.ChunkEntity
import com.example.data.local.DocuMindDatabase
import com.example.data.local.DocumentEntity
import com.example.domain.rag.LocalVectorEngine
import com.example.domain.rag.OllamaStatus
import com.example.domain.rag.RetrievedChunkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RagConfigState(
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 100,
    val topK: Int = 3,
    val similarityThreshold: Float = 0.24f,
    val ollamaHost: String = "http://10.0.2.2:11434",
    val selectedModel: String = "llama3.2",
    val temperature: Float = 0.1f,
    val isDarkTheme: Boolean = true
)

data class PipelineLabState(
    val sampleQuery: String = "How often are cryptographic master keys rotated and how long are backups retained?",
    val extractedTokens: List<String> = emptyList(),
    val queryVectorPreview: List<Float> = emptyList(),
    val retrievedResults: List<RetrievedChunkResult> = emptyList(),
    val augmentedSystemPrompt: String = "",
    val augmentedUserPrompt: String = ""
)

class DocuMindViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DocuMindDatabase.getInstance(application)
    private val repository = DocuMindRepository(application, database.dao())

    private val _config = MutableStateFlow(RagConfigState())
    val config: StateFlow<RagConfigState> = _config.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _ollamaStatus = MutableStateFlow(
        OllamaStatus(
            isReachable = false,
            hostUrl = "http://10.0.2.2:11434",
            availableModels = listOf("llama3.2", "mistral", "phi3", "gemma2:2b"),
            statusMessage = "Local Grounded Synthesis Ready (Configure Ollama LAN/Host URL to link external daemon)"
        )
    )
    val ollamaStatus: StateFlow<OllamaStatus> = _ollamaStatus.asStateFlow()

    private val _pipelineLab = MutableStateFlow(PipelineLabState())
    val pipelineLab: StateFlow<PipelineLabState> = _pipelineLab.asStateFlow()

    val documents: StateFlow<List<DocumentEntity>> = repository.documentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chunks: StateFlow<List<ChunkEntity>> = repository.chunksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatHistory: StateFlow<List<ChatMessageItem>> = repository.rawChatHistoryFlow
        .map { entities ->
            entities.map { entity ->
                ChatMessageItem(
                    id = entity.id,
                    role = entity.role,
                    content = entity.content,
                    sources = DocuMindRepository.deserializeSources(entity.sourcesSerialized),
                    isGrounded = entity.isGrounded,
                    modelUsed = entity.modelUsed,
                    latencyMs = entity.latencyMs,
                    timestamp = entity.timestamp
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val cfg = _config.value
                repository.ensureDefaultDocumentsLoaded(cfg.chunkSize, cfg.chunkOverlap)
                refreshPipelineLab(_pipelineLab.value.sampleQuery)
            } catch (e: Exception) {
                _statusBanner.value = "Initialization warning: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }

        // Automatically refresh Pipeline Lab whenever chunks finish loading
        viewModelScope.launch {
            combine(chunks, _config) { chunkList, _ -> chunkList.size }
                .collect { count ->
                    if (count > 0) {
                        refreshPipelineLab(_pipelineLab.value.sampleQuery)
                    }
                }
        }
    }

    fun toggleTheme() {
        _config.value = _config.value.copy(isDarkTheme = !_config.value.isDarkTheme)
    }

    fun updateChunkSettings(chunkSize: Int, chunkOverlap: Int) {
        val validSize = chunkSize.coerceIn(200, 1500)
        val validOverlap = chunkOverlap.coerceIn(25, validSize - 50)
        _config.value = _config.value.copy(chunkSize = validSize, chunkOverlap = validOverlap)
    }

    fun updateRetrievalSettings(topK: Int, similarityThreshold: Float) {
        _config.value = _config.value.copy(
            topK = topK.coerceIn(1, 8),
            similarityThreshold = similarityThreshold.coerceIn(0.05f, 0.75f)
        )
        refreshPipelineLab(_pipelineLab.value.sampleQuery)
    }

    fun updateOllamaSettings(hostUrl: String, modelName: String, temperature: Float) {
        _config.value = _config.value.copy(
            ollamaHost = hostUrl.trim(),
            selectedModel = modelName.trim().ifEmpty { "llama3.2" },
            temperature = temperature.coerceIn(0f, 1f)
        )
    }

    fun testOllamaConnection() {
        viewModelScope.launch {
            _isBusy.value = true
            val status = repository.checkOllama(_config.value.ollamaHost)
            _ollamaStatus.value = status
            _statusBanner.value = status.statusMessage
            _isBusy.value = false
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun uploadPdfUri(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val context = getApplication<Application>()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Could not open input stream for '$displayName'.")
                val cfg = _config.value
                val (pagesCount, chunksCount) = repository.ingestPdfBytes(
                    pdfBytes = bytes,
                    documentName = displayName,
                    chunkSize = cfg.chunkSize,
                    chunkOverlap = cfg.chunkOverlap
                )
                _statusBanner.value =
                    "Indexed '$displayName': $pagesCount pages extracted, $chunksCount overlapping chunks embedded into 384-d vector store."
                refreshPipelineLab(_pipelineLab.value.sampleQuery)
            } catch (e: Exception) {
                _statusBanner.value = "PDF Ingestion Error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun restoreSamplePdfs() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val cfg = _config.value
                repository.restoreDefaultSamplePdfs(cfg.chunkSize, cfg.chunkOverlap)
                _statusBanner.value =
                    "Restored 3 multi-page technical PDFs (9 pages total) and rebuilt local 384-d vector index."
                refreshPipelineLab(_pipelineLab.value.sampleQuery)
            } catch (e: Exception) {
                _statusBanner.value = "Error restoring sample PDFs: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            repository.deleteDocument(doc.id)
            _statusBanner.value = "Removed '${doc.fileName}' and its vectors from the index."
        }
    }

    fun clearAllDocuments() {
        viewModelScope.launch {
            repository.clearAllDocuments()
            _statusBanner.value = "Cleared all documents and reset local vector store."
        }
    }

    fun askQuestion(question: String) {
        if (question.isBlank() || _isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val cfg = _config.value
                repository.askQuestion(
                    question = question,
                    topK = cfg.topK,
                    similarityThreshold = cfg.similarityThreshold,
                    ollamaHost = cfg.ollamaHost,
                    modelName = cfg.selectedModel,
                    temperature = cfg.temperature
                )
                refreshPipelineLab(question)
            } catch (e: Exception) {
                _statusBanner.value = "Query Error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearChatHistory()
        }
    }

    fun refreshPipelineLab(query: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val tokens = LocalVectorEngine.tokenizeMeaningfulTerms(query)
            val vec = LocalVectorEngine.embedText384(query).take(24)
            val allChunks = chunks.value
            val cfg = _config.value
            val retrieved = LocalVectorEngine.searchTopK(
                query = query,
                chunks = allChunks,
                topK = cfg.topK,
                similarityThreshold = cfg.similarityThreshold
            )
            val (sysPrompt, usrPrompt) = com.example.domain.rag.OllamaClient().buildGroundedPrompts(
                question = query,
                retrievedChunks = retrieved
            )
            _pipelineLab.value = PipelineLabState(
                sampleQuery = query,
                extractedTokens = tokens,
                queryVectorPreview = vec,
                retrievedResults = retrieved,
                augmentedSystemPrompt = sysPrompt,
                augmentedUserPrompt = usrPrompt
            )
        }
    }
}
