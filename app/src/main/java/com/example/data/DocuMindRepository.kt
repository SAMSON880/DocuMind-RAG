package com.example.data

import android.content.Context
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChunkEntity
import com.example.data.local.DocuMindDao
import com.example.data.local.DocumentEntity
import com.example.domain.rag.GroundedGenerationResult
import com.example.domain.rag.LocalVectorEngine
import com.example.domain.rag.OllamaClient
import com.example.domain.rag.OllamaStatus
import com.example.domain.rag.PdfExtractorAndGenerator
import com.example.domain.rag.RetrievedChunkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessageItem(
    val id: Long,
    val role: String,
    val content: String,
    val sources: List<RetrievedChunkResult>,
    val isGrounded: Boolean,
    val modelUsed: String,
    val latencyMs: Long,
    val timestamp: Long
)

class DocuMindRepository(
    private val context: Context,
    private val dao: DocuMindDao,
    private val ollamaClient: OllamaClient = OllamaClient()
) {
    val documentsFlow: Flow<List<DocumentEntity>> = dao.observeAllDocuments()
    val chunksFlow: Flow<List<ChunkEntity>> = dao.observeAllChunks()
    val rawChatHistoryFlow: Flow<List<ChatMessageEntity>> = dao.observeChatHistory()

    suspend fun ensureDefaultDocumentsLoaded(chunkSize: Int, chunkOverlap: Int) = withContext(Dispatchers.IO) {
        val currentDocs = dao.getAllDocuments()
        if (currentDocs.isNotEmpty()) return@withContext

        val samples = PdfExtractorAndGenerator.getDefaultSamplePdfs()
        for ((fileName, pageStrings) in samples) {
            val realPdfBytes = PdfExtractorAndGenerator.createRealPdfBytes(pageStrings)
            ingestPdfBytes(
                pdfBytes = realPdfBytes,
                documentName = fileName,
                chunkSize = chunkSize,
                chunkOverlap = chunkOverlap
            )
        }
    }

    suspend fun restoreDefaultSamplePdfs(chunkSize: Int, chunkOverlap: Int) = withContext(Dispatchers.IO) {
        val samples = PdfExtractorAndGenerator.getDefaultSamplePdfs()
        for ((fileName, pageStrings) in samples) {
            val realPdfBytes = PdfExtractorAndGenerator.createRealPdfBytes(pageStrings)
            ingestPdfBytes(
                pdfBytes = realPdfBytes,
                documentName = fileName,
                chunkSize = chunkSize,
                chunkOverlap = chunkOverlap
            )
        }
    }

    suspend fun ingestPdfBytes(
        pdfBytes: ByteArray,
        documentName: String,
        chunkSize: Int,
        chunkOverlap: Int
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val existing = dao.getDocumentByName(documentName)
        if (existing != null) {
            dao.deleteDocumentById(existing.id)
        }

        val extractedPages = PdfExtractorAndGenerator.extractPagesFromPdfBytes(
            context = context,
            pdfBytes = pdfBytes,
            documentName = documentName
        )

        val rawChunks = LocalVectorEngine.chunkPages(
            pages = extractedPages,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap
        )

        val totalChars = extractedPages.sumOf { it.text.length }
        val docId = dao.insertDocument(
            DocumentEntity(
                fileName = documentName,
                pageCount = extractedPages.size,
                chunkCount = rawChunks.size,
                totalChars = totalChars
            )
        )

        val chunkEntities = rawChunks.map { rc ->
            ChunkEntity(
                documentId = docId,
                documentName = rc.documentName,
                pageNumber = rc.pageNumber,
                chunkIndexInPage = rc.chunkIndexInPage,
                charStart = rc.charStart,
                charEnd = rc.charEnd,
                text = rc.text,
                embeddingCsv = LocalVectorEngine.serializeVector(rc.embedding)
            )
        }

        dao.insertChunks(chunkEntities)
        extractedPages.size to rawChunks.size
    }

    suspend fun deleteDocument(docId: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocumentById(docId)
    }

    suspend fun clearAllDocuments() = withContext(Dispatchers.IO) {
        dao.deleteAllDocuments()
    }

    suspend fun clearChatHistory() = withContext(Dispatchers.IO) {
        dao.clearChatHistory()
    }

    suspend fun checkOllama(hostUrl: String): OllamaStatus {
        return ollamaClient.checkOllamaServer(hostUrl)
    }

    suspend fun askQuestion(
        question: String,
        topK: Int,
        similarityThreshold: Float,
        ollamaHost: String,
        modelName: String,
        temperature: Float
    ): ChatMessageItem = withContext(Dispatchers.IO) {
        val trimmed = question.trim()
        dao.insertChatMessage(
            ChatMessageEntity(
                role = "user",
                content = trimmed
            )
        )

        val allChunks = dao.getAllChunks()
        if (allChunks.isEmpty()) {
            val noDocsMsg = "Please upload or load at least one PDF document before asking questions."
            val id = dao.insertChatMessage(
                ChatMessageEntity(
                    role = "assistant",
                    content = noDocsMsg,
                    sourcesSerialized = "[]",
                    isGrounded = false,
                    modelUsed = modelName,
                    latencyMs = 0L
                )
            )
            return@withContext ChatMessageItem(
                id = id,
                role = "assistant",
                content = noDocsMsg,
                sources = emptyList(),
                isGrounded = false,
                modelUsed = modelName,
                latencyMs = 0L,
                timestamp = System.currentTimeMillis()
            )
        }

        val retrieved = LocalVectorEngine.searchTopK(
            query = trimmed,
            chunks = allChunks,
            topK = topK,
            similarityThreshold = similarityThreshold
        )

        val generation: GroundedGenerationResult = ollamaClient.generateGroundedAnswer(
            question = trimmed,
            retrievedChunks = retrieved,
            ollamaHost = ollamaHost,
            modelName = modelName,
            temperature = temperature
        )

        val serializedSources = serializeSources(retrieved)
        val msgId = dao.insertChatMessage(
            ChatMessageEntity(
                role = "assistant",
                content = generation.answer,
                sourcesSerialized = serializedSources,
                isGrounded = generation.isGrounded,
                modelUsed = generation.engineLabel,
                latencyMs = generation.latencyMs
            )
        )

        ChatMessageItem(
            id = msgId,
            role = "assistant",
            content = generation.answer,
            sources = retrieved,
            isGrounded = generation.isGrounded,
            modelUsed = generation.engineLabel,
            latencyMs = generation.latencyMs,
            timestamp = System.currentTimeMillis()
        )
    }

    companion object {
        fun serializeSources(sources: List<RetrievedChunkResult>): String {
            val arr = JSONArray()
            for (s in sources) {
                val obj = JSONObject().apply {
                    put("chunkId", s.chunkId)
                    put("documentName", s.documentName)
                    put("pageNumber", s.pageNumber)
                    put("chunkIndexInPage", s.chunkIndexInPage)
                    put("charStart", s.charStart)
                    put("charEnd", s.charEnd)
                    put("text", s.text)
                    put("cosineSimilarity", s.cosineSimilarity.toDouble())
                    put("matchedTerms", JSONArray(s.matchedTerms))
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeSources(jsonStr: String): List<RetrievedChunkResult> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val out = mutableListOf<RetrievedChunkResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val termsArr = obj.optJSONArray("matchedTerms") ?: JSONArray()
                    val terms = mutableListOf<String>()
                    for (t in 0 until termsArr.length()) {
                        terms.add(termsArr.optString(t))
                    }
                    out.add(
                        RetrievedChunkResult(
                            chunkId = obj.optLong("chunkId"),
                            documentName = obj.optString("documentName"),
                            pageNumber = obj.optInt("pageNumber", 1),
                            chunkIndexInPage = obj.optInt("chunkIndexInPage", 0),
                            charStart = obj.optInt("charStart", 0),
                            charEnd = obj.optInt("charEnd", 0),
                            text = obj.optString("text"),
                            cosineSimilarity = obj.optDouble("cosineSimilarity", 0.0).toFloat(),
                            matchedTerms = terms
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
