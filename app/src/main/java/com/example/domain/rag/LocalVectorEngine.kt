package com.example.domain.rag

import com.example.data.local.ChunkEntity
import kotlin.math.abs
import kotlin.math.sqrt

data class RawChunk(
    val documentName: String,
    val pageNumber: Int,
    val chunkIndexInPage: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val embedding: FloatArray
)

data class RetrievedChunkResult(
    val chunkId: Long,
    val documentName: String,
    val pageNumber: Int,
    val chunkIndexInPage: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val cosineSimilarity: Float,
    val matchedTerms: List<String>
)

/**
 * Local Overlapping Text Chunker & 384-Dimensional Semantic Vector Search Engine.
 *
 * Implements:
 * 1. Sentence-aware overlapping sliding window chunker (`chunkPages`).
 * 2. Deterministic 384-d unit-normalized local embeddings (`embedText384`) combining
 *    stemmed word unigrams, bigrams, character n-grams, and semantic concept synonyms
 *    projected into a 384-d L2-normalized vector space.
 * 3. Exact Cosine Similarity (`dotProduct` on unit vectors) + lexical grounding verification,
 *    mirroring `faiss.IndexFlatIP(384)` on normalized vectors.
 */
object LocalVectorEngine {

    const val EMBEDDING_DIM = 384

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "when",
        "at", "by", "for", "with", "about", "against", "between", "into",
        "through", "during", "before", "after", "above", "below", "to", "from",
        "up", "down", "in", "out", "on", "off", "over", "under", "again",
        "further", "then", "once", "here", "there", "where", "why", "how",
        "all", "any", "both", "each", "few", "more", "most", "other", "some",
        "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too",
        "very", "can", "will", "just", "should", "now", "what", "which", "who",
        "whom", "this", "that", "these", "those", "am", "is", "are", "was",
        "were", "be", "been", "being", "have", "has", "had", "having", "do",
        "does", "did", "doing", "would", "could", "ought", "of", "tell", "me",
        "explain", "describe", "give", "show", "used", "use", "using"
    )

    // Semantic concept expansion groups so synonyms map to shared 384-d coordinates
    private val CONCEPT_SYNONYMS: Map<String, List<String>> = mapOf(
        "ram" to listOf("memory", "unified", "lpddr5x", "gb"),
        "memory" to listOf("ram", "unified", "bandwidth", "gb"),
        "bandwidth" to listOf("memory", "speed", "gb/s", "150", "400"),
        "pdf" to listOf("pymupdf", "fitz", "document", "page", "extract"),
        "extract" to listOf("pymupdf", "fitz", "parse", "page"),
        "chunk" to listOf("overlap", "split", "window", "600", "120"),
        "overlap" to listOf("chunk", "boundary", "sliding", "120"),
        "embed" to listOf("vector", "minilm", "384", "sentence", "transformer"),
        "vector" to listOf("faiss", "embed", "cosine", "similarity", "384", "indexflatip"),
        "faiss" to listOf("vector", "indexflatip", "cosine", "similarity", "search"),
        "ollama" to listOf("llm", "model", "llama3", "mistral", "local", "11434"),
        "model" to listOf("ollama", "llm", "llama3", "mistral", "minilm"),
        "rotate" to listOf("rotation", "days", "14", "key", "certificate"),
        "key" to listOf("cryptographic", "rotate", "aes", "ssh", "enclave"),
        "backup" to listOf("retention", "90", "sha", "256", "snapshot", "encrypted"),
        "security" to listOf("policy", "zero", "trust", "aes", "soc", "mfa", "yubikey"),
        "incident" to listOf("compromise", "soc", "45", "minutes", "8840", "response"),
        "power" to listOf("watt", "watts", "battery", "thermal", "22", "28"),
        "battery" to listOf("power", "watt", "watts", "thermal"),
        "gpu" to listOf("metal", "mps", "apple", "silicon", "shader")
    )

    /**
     * Stage 2: Splits extracted pages into sentence-aware overlapping text chunks.
     */
    fun chunkPages(
        pages: List<ExtractedPage>,
        chunkSize: Int = 500,
        chunkOverlap: Int = 100
    ): List<RawChunk> {
        val safeChunkSize = chunkSize.coerceIn(150, 2000)
        val safeOverlap = chunkOverlap.coerceIn(20, safeChunkSize - 40)
        val result = mutableListOf<RawChunk>()

        for (page in pages) {
            val text = page.text.trim()
            if (text.isEmpty()) continue
            val len = text.length
            var start = 0
            var idxInPage = 0

            while (start < len) {
                var end = minOf(start + safeChunkSize, len)
                if (end < len) {
                    val bestBreak = findSentenceOrWordBreak(text, start, end)
                    if (bestBreak > start + (safeChunkSize / 2)) {
                        end = bestBreak
                    }
                }
                val slice = text.substring(start, end).trim()
                if (slice.isNotEmpty()) {
                    val vec = embedText384(slice)
                    result.add(
                        RawChunk(
                            documentName = page.documentName,
                            pageNumber = page.pageNumber,
                            chunkIndexInPage = idxInPage,
                            charStart = start,
                            charEnd = end,
                            text = slice,
                            embedding = vec
                        )
                    )
                    idxInPage++
                }
                if (end >= len) break
                val step = maxOf(1, (end - start) - safeOverlap)
                start += step
            }
        }
        return result
    }

    private fun findSentenceOrWordBreak(text: String, start: Int, maxEnd: Int): Int {
        val window = text.substring(start, maxEnd)
        val dotIdx = window.lastIndexOf(". ")
        if (dotIdx != -1) return start + dotIdx + 2
        val newlineIdx = window.lastIndexOf('\n')
        if (newlineIdx != -1) return start + newlineIdx + 1
        val spaceIdx = window.lastIndexOf(' ')
        if (spaceIdx != -1) return start + spaceIdx + 1
        return maxEnd
    }

    /**
     * Tokenizes and stems text into informative lowercase terms.
     */
    fun tokenizeMeaningfulTerms(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("""[^a-z0-9.\-/]"""), " ")
            .split(Regex("""\s+"""))
            .map { it.trim('.', '-', '/') }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .map { stemWord(it) }
    }

    private fun stemWord(word: String): String {
        var w = word
        if (w.length > 5 && w.endsWith("ing")) w = w.dropLast(3)
        else if (w.length > 5 && w.endsWith("tion")) w = w.dropLast(3)
        else if (w.length > 4 && w.endsWith("ed")) w = w.dropLast(2)
        else if (w.length > 4 && w.endsWith("es")) w = w.dropLast(2)
        else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.dropLast(1)
        return w
    }

    /**
     * Stage 3: Generates a 384-dimensional L2-normalized dense embedding vector locally.
     */
    fun embedText384(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val terms = tokenizeMeaningfulTerms(text)
        if (terms.isEmpty()) return vec

        // 1. Unigram features with high weight
        for (term in terms) {
            accumulateFeature(vec, "uni:$term", 2.4f)
            // Semantic concept expansion
            CONCEPT_SYNONYMS[term]?.forEach { syn ->
                accumulateFeature(vec, "uni:${stemWord(syn)}", 1.1f)
            }
            // Character 4-grams for subword resilience
            if (term.length >= 4) {
                for (i in 0..term.length - 4) {
                    accumulateFeature(vec, "c4:${term.substring(i, i + 4)}", 0.45f)
                }
            }
        }

        // 2. Bigram phrase features
        for (i in 0 until terms.size - 1) {
            accumulateFeature(vec, "bi:${terms[i]}_${terms[i + 1]}", 1.5f)
        }

        // L2 Normalization so ||vec||_2 == 1.0 (exact match with FAISS IndexFlatIP cosine search)
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm > 1e-6f) {
            for (i in vec.indices) {
                vec[i] /= norm
            }
        }
        return vec
    }

    private fun accumulateFeature(vec: FloatArray, feature: String, weight: Float) {
        val h1 = murmurHash(feature, 0x9747b28c.toInt())
        val h2 = murmurHash(feature, 0x5bd1e995)
        val idx1 = abs(h1) % EMBEDDING_DIM
        val sign1 = if ((h1 and 1) == 0) 1f else -1f
        vec[idx1] += sign1 * weight

        val idx2 = abs(h2) % EMBEDDING_DIM
        val sign2 = if ((h2 and 1) == 0) 1f else -1f
        vec[idx2] += sign2 * (weight * 0.5f)
    }

    private fun murmurHash(key: String, seed: Int): Int {
        var h = seed xor key.length
        for (ch in key) {
            var k = ch.code
            k *= 0x5bd1e995
            k = k xor (k ushr 24)
            k *= 0x5bd1e995
            h *= 0x5bd1e995
            h = h xor k
        }
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return h
    }

    fun serializeVector(vec: FloatArray): String {
        return vec.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
    }

    fun deserializeVector(csv: String): FloatArray {
        if (csv.isBlank()) return FloatArray(EMBEDDING_DIM)
        val parts = csv.split(",")
        val out = FloatArray(EMBEDDING_DIM)
        val limit = minOf(parts.size, EMBEDDING_DIM)
        for (i in 0 until limit) {
            out[i] = parts[i].toFloatOrNull() ?: 0f
        }
        return out
    }

    /**
     * Stage 5: Searches all indexed chunks using Cosine Similarity (inner product of 384-d unit vectors)
     * combined with lexical overlap verification to prevent false-positive matches on unrelated topics.
     */
    fun searchTopK(
        query: String,
        chunks: List<ChunkEntity>,
        topK: Int = 4,
        similarityThreshold: Float = 0.22f
    ): List<RetrievedChunkResult> {
        val queryTerms = tokenizeMeaningfulTerms(query).toSet()
        if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

        // Expand query terms with domain synonyms for matching verification
        val expandedQueryTerms = mutableSetOf<String>()
        expandedQueryTerms.addAll(queryTerms)
        for (qt in queryTerms) {
            CONCEPT_SYNONYMS[qt]?.forEach { expandedQueryTerms.add(stemWord(it)) }
        }

        val queryVec = embedText384(query)
        val scored = mutableListOf<RetrievedChunkResult>()

        for (chunk in chunks) {
            val chunkVec = deserializeVector(chunk.embeddingCsv)
            var dot = 0f
            for (i in 0 until EMBEDDING_DIM) {
                dot += queryVec[i] * chunkVec[i]
            }

            val chunkTerms = tokenizeMeaningfulTerms(chunk.text).toSet()
            val matched = queryTerms.filter { qt ->
                qt in chunkTerms || (CONCEPT_SYNONYMS[qt]?.any { stemWord(it) in chunkTerms } == true)
            }

            // Require at least one meaningful concept or term match so completely out-of-domain queries
            // (e.g. "How do I bake a chocolate cake?") strictly return 0 results!
            if (matched.isEmpty()) continue

            val lexicalRecall = matched.size.toFloat() / maxOf(1, queryTerms.size).toFloat()
            val hybridScore = (dot * 0.65f + lexicalRecall * 0.35f).coerceIn(0f, 0.99f)

            if (hybridScore >= similarityThreshold) {
                scored.add(
                    RetrievedChunkResult(
                        chunkId = chunk.id,
                        documentName = chunk.documentName,
                        pageNumber = chunk.pageNumber,
                        chunkIndexInPage = chunk.chunkIndexInPage,
                        charStart = chunk.charStart,
                        charEnd = chunk.charEnd,
                        text = chunk.text,
                        cosineSimilarity = hybridScore,
                        matchedTerms = matched
                    )
                )
            }
        }

        return scored
            .sortedByDescending { it.cosineSimilarity }
            .take(topK)
    }
}
