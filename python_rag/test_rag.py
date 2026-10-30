"""
Automated Unit & Integration Tests for DocuMind RAG Engine.
Run on macOS with:
    pytest test_rag.py -v
"""

import fitz  # PyMuPDF
import pytest

from rag_engine import DocuMindRAGEngine, NOT_FOUND_RESPONSE


def create_sample_pdf_bytes() -> bytes:
    """Dynamically creates a valid 2-page PDF in memory using PyMuPDF."""
    doc = fitz.open()

    page1 = doc.new_page()
    page1.insert_text(
        (50, 72),
        "Project Aurora Technical Specification\n"
        "Aurora uses a 384-dimensional FAISS IndexFlatIP vector store running locally on macOS.\n"
        "The maximum allowed memory budget for the edge cache is 512 MB.",
    )

    page2 = doc.new_page()
    page2.insert_text(
        (50, 72),
        "Security & Backup Policy\n"
        "All encrypted snapshots are rotated every 14 days and verified using SHA-256 checksums.\n"
        "Offline mode guarantees zero external cloud telemetry.",
    )

    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes


def test_pdf_extraction_preserves_page_numbers():
    engine = DocuMindRAGEngine(chunk_size=120, chunk_overlap=30)
    pdf_bytes = create_sample_pdf_bytes()

    pages = engine.extract_pages_from_pdf(pdf_bytes, "aurora_spec.pdf")
    assert len(pages) == 2
    assert pages[0].page_number == 1
    assert "512 MB" in pages[0].text
    assert pages[1].page_number == 2
    assert "14 days" in pages[1].text


def test_overlapping_chunking_and_faiss_retrieval():
    engine = DocuMindRAGEngine(chunk_size=100, chunk_overlap=25, similarity_threshold=0.20)
    pdf_bytes = create_sample_pdf_bytes()

    num_pages, num_chunks = engine.ingest_pdf(pdf_bytes, "aurora_spec.pdf")
    assert num_pages == 2
    assert num_chunks >= 2
    assert engine.index.ntotal == num_chunks

    # Query specifically targeting Page 2
    results = engine.retrieve("How often are encrypted snapshots rotated?", top_k=2)
    assert len(results) >= 1
    assert results[0].chunk.document_name == "aurora_spec.pdf"
    assert results[0].chunk.page_number == 2
    assert "14 days" in results[0].chunk.text


def test_unrelated_query_returns_not_found():
    engine = DocuMindRAGEngine(chunk_size=150, chunk_overlap=30, similarity_threshold=0.45)
    pdf_bytes = create_sample_pdf_bytes()
    engine.ingest_pdf(pdf_bytes, "aurora_spec.pdf")

    # Ask an unrelated question about culinary recipes
    response = engine.answer_question("How do I bake a chocolate soufflé in a convection oven?")
    assert response.is_grounded is False
    assert response.answer == NOT_FOUND_RESPONSE
    assert len(response.retrieved_chunks) == 0


def test_empty_or_corrupt_pdf_raises_value_error():
    engine = DocuMindRAGEngine()
    with pytest.raises(ValueError, match="empty"):
        engine.extract_pages_from_pdf(b"", "empty.pdf")

    with pytest.raises(ValueError, match="Failed to open"):
        engine.extract_pages_from_pdf(b"not-a-valid-pdf-header", "corrupt.pdf")
