package com.example

import com.example.data.local.ChunkEntity
import com.example.domain.rag.ExtractedPage
import com.example.domain.rag.LocalVectorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ExampleUnitTest {

    @Test
    fun `overlapping chunking preserves page numbers and creates 384d unit vectors`() {
        val pages = listOf(
            ExtractedPage(
                documentName = "spec.pdf",
                pageNumber = 1,
                text = "Sentence one explains PyMuPDF page extraction. Sentence two explains overlapping chunks in detail. Sentence three covers FAISS cosine similarity."
            ),
            ExtractedPage(
                documentName = "spec.pdf",
                pageNumber = 2,
                text = "Cryptographic keys are rotated every 14 days and backups are retained for 90 days."
            )
        )

        val chunks = LocalVectorEngine.chunkPages(pages, chunkSize = 80, chunkOverlap = 25)
        assertTrue(chunks.size >= 2)
        assertEquals(1, chunks.first().pageNumber)
        assertEquals(2, chunks.last().pageNumber)

        val vec = chunks.first().embedding
        assertEquals(384, vec.size)
        val norm = sqrt(vec.fold(0f) { acc, v -> acc + v * v })
        assertEquals(1.0f, norm, 0.01f)
    }

    @Test
    fun `vector search retrieves relevant page and rejects unrelated queries`() {
        val pages = listOf(
            ExtractedPage(
                documentName = "security.pdf",
                pageNumber = 2,
                text = "Cryptographic master keys must be rotated every 14 days via the hardware Secure Enclave."
            )
        )
        val rawChunks = LocalVectorEngine.chunkPages(pages, chunkSize = 200, chunkOverlap = 40)
        val entities = rawChunks.mapIndexed { idx, rc ->
            ChunkEntity(
                id = idx.toLong() + 1L,
                documentId = 1L,
                documentName = rc.documentName,
                pageNumber = rc.pageNumber,
                chunkIndexInPage = rc.chunkIndexInPage,
                charStart = rc.charStart,
                charEnd = rc.charEnd,
                text = rc.text,
                embeddingCsv = LocalVectorEngine.serializeVector(rc.embedding)
            )
        }

        val hit = LocalVectorEngine.searchTopK(
            query = "How often are cryptographic master keys rotated?",
            chunks = entities,
            topK = 3,
            similarityThreshold = 0.20f
        )
        assertEquals(1, hit.size)
        assertEquals("security.pdf", hit.first().documentName)
        assertEquals(2, hit.first().pageNumber)

        val miss = LocalVectorEngine.searchTopK(
            query = "How do I bake a chocolate soufflé in a convection oven?",
            chunks = entities,
            topK = 3,
            similarityThreshold = 0.20f
        )
        assertTrue(miss.isEmpty())
    }
}
