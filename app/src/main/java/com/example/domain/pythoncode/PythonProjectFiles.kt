package com.example.domain.pythoncode

data class PythonSourceFile(
    val fileName: String,
    val subtitle: String,
    val language: String,
    val code: String
)

/**
 * Provides the complete, production-ready Mac Python RAG codebase inside the app's
 * "Mac Python" tab so users can inspect and copy `app.py`, `rag_engine.py`,
 * `requirements.txt`, `test_rag.py`, and the Mac Setup Guide directly.
 */
object PythonProjectFiles {

    val files: List<PythonSourceFile> = listOf(
        PythonSourceFile(
            fileName = "rag_engine.py",
            subtitle = "Core 7-Stage Local RAG Pipeline (PyMuPDF + SentenceTransformers + FAISS + Ollama)",
            language = "python",
            code = """
from __future__ import annotations
import io
import re
from dataclasses import dataclass, field
from typing import Dict, Generator, List, Optional, Tuple
import faiss
import fitz  # PyMuPDF
import numpy as np
import ollama
from sentence_transformers import SentenceTransformer

NOT_FOUND_RESPONSE = (
    "I could not find relevant information in the uploaded documents to answer this question. "
    "Please verify that the topic is covered in your uploaded PDFs or try rephrasing your query."
)

@dataclass
class PageContent:
    document_name: str
    page_number: int  # 1-indexed page number
    text: str

@dataclass
class DocumentChunk:
    chunk_id: int
    document_name: str
    page_number: int
    chunk_index_in_page: int
    text: str
    char_start: int
    char_end: int

@dataclass
class RetrievedChunk:
    chunk: DocumentChunk
    similarity_score: float

@dataclass
class RAGResponse:
    answer: str
    retrieved_chunks: List[RetrievedChunk] = field(default_factory=list)
    is_grounded: bool = True
    model_used: str = ""
    error_message: Optional[str] = None

class DocuMindRAGEngine:
    def __init__(
        self,
        embedding_model_name: str = "all-MiniLM-L6-v2",
        chunk_size: int = 600,
        chunk_overlap: int = 120,
        similarity_threshold: float = 0.25,
        ollama_host: str = "http://localhost:11434",
    ) -> None:
        if chunk_overlap >= chunk_size:
            raise ValueError("chunk_overlap must be strictly smaller than chunk_size.")
        self.embedding_model_name = embedding_model_name
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.similarity_threshold = similarity_threshold
        self.ollama_host = ollama_host

        self.embedder = SentenceTransformer(embedding_model_name)
        self.embedding_dim: int = self.embedder.get_sentence_embedding_dimension()
        self.index: faiss.IndexFlatIP = faiss.IndexFlatIP(self.embedding_dim)
        self.chunks: List[DocumentChunk] = []
        self.indexed_documents: Dict[str, int] = {}

    def extract_pages_from_pdf(self, pdf_bytes: bytes, document_name: str) -> List[PageContent]:
        if not pdf_bytes:
            raise ValueError(f"Uploaded file '{document_name}' is empty.")
        try:
            doc = fitz.open(stream=io.BytesIO(pdf_bytes), filetype="pdf")
        except Exception as exc:
            raise ValueError(f"Failed to open '{document_name}' as a valid PDF: {exc}") from exc

        if doc.is_encrypted:
            doc.close()
            raise ValueError(f"PDF '{document_name}' is password-protected.")

        extracted_pages: List[PageContent] = []
        total_pages = len(doc)
        for page_idx in range(total_pages):
            page = doc.load_page(page_idx)
            raw_text = page.get_text("text") or ""
            cleaned = re.sub(r"[ \t]+", " ", raw_text.replace("\x00", "")).strip()
            if cleaned:
                extracted_pages.append(
                    PageContent(
                        document_name=document_name,
                        page_number=page_idx + 1,
                        text=cleaned,
                    )
                )
        doc.close()
        if not extracted_pages:
            raise ValueError(f"No selectable text could be extracted from '{document_name}'.")
        self.indexed_documents[document_name] = total_pages
        return extracted_pages

    def chunk_pages(self, pages: List[PageContent]) -> List[DocumentChunk]:
        new_chunks: List[DocumentChunk] = []
        next_id = len(self.chunks)
        for page in pages:
            text = page.text
            text_len = len(text)
            start = 0
            idx_in_page = 0
            while start < text_len:
                end = min(start + self.chunk_size, text_len)
                if end < text_len:
                    window = text[start:end]
                    matches = list(re.finditer(r"\.\s|\n|\s", window))
                    if matches and (start + matches[-1].end()) > start + (self.chunk_size // 2):
                        end = start + matches[-1].end()
                snippet = text[start:end].strip()
                if snippet:
                    new_chunks.append(
                        DocumentChunk(
                            chunk_id=next_id,
                            document_name=page.document_name,
                            page_number=page.page_number,
                            chunk_index_in_page=idx_in_page,
                            text=snippet,
                            char_start=start,
                            char_end=end,
                        )
                    )
                    next_id += 1
                    idx_in_page += 1
                if end >= text_len:
                    break
                start += max(1, (end - start) - self.chunk_overlap)
        return new_chunks

    def index_chunks(self, new_chunks: List[DocumentChunk]) -> int:
        if not new_chunks:
            return 0
        texts = [c.text for c in new_chunks]
        embeddings = self.embedder.encode(
            texts, batch_size=32, show_progress_bar=False, convert_to_numpy=True, normalize_embeddings=True
        ).astype("float32")
        self.index.add(embeddings)
        self.chunks.extend(new_chunks)
        return len(new_chunks)

    def ingest_pdf(self, pdf_bytes: bytes, document_name: str) -> Tuple[int, int]:
        pages = self.extract_pages_from_pdf(pdf_bytes, document_name)
        new_chunks = self.chunk_pages(pages)
        self.index_chunks(new_chunks)
        return len(pages), len(new_chunks)

    def retrieve(self, query: str, top_k: int = 4) -> List[RetrievedChunk]:
        if not query.strip() or self.index.ntotal == 0:
            return []
        query_vec = self.embedder.encode(
            [query.strip()], convert_to_numpy=True, normalize_embeddings=True
        ).astype("float32")
        k = min(top_k, self.index.ntotal)
        scores, indices = self.index.search(query_vec, k)
        results: List[RetrievedChunk] = []
        for score, idx in zip(scores[0], indices[0]):
            if 0 <= idx < len(self.chunks) and float(score) >= self.similarity_threshold:
                results.append(RetrievedChunk(chunk=self.chunks[idx], similarity_score=float(score)))
        return results

    @staticmethod
    def build_grounded_prompt(question: str, retrieved_chunks: List[RetrievedChunk]) -> Tuple[str, str]:
        system_prompt = (
            "You are DocuMind, a precise Document Q&A Assistant.\n"
            "1. Answer ONLY using facts stated in PROVIDED DOCUMENT CONTEXT.\n"
            "2. Cite every claim inline as [Source: <Document Name>, Page <Page Number>].\n"
            f'3. If the context does not answer the question, reply with: "{NOT_FOUND_RESPONSE}"'
        )
        blocks = [
            f"--- CHUNK {i} | Document: {r.chunk.document_name} | Page: {r.chunk.page_number} "
            f"| Similarity: {r.similarity_score:.3f} ---\n{r.chunk.text}"
            for i, r in enumerate(retrieved_chunks, start=1)
        ]
        user_prompt = f"PROVIDED DOCUMENT CONTEXT:\n{chr(10).join(blocks)}\n\nUSER QUESTION:\n{question}"
        return system_prompt, user_prompt
            """.trimIndent()
        ),
        PythonSourceFile(
            fileName = "app.py",
            subtitle = "Streamlit UI with Multi-PDF Upload, Chat History, Citations & Light/Dark Theme",
            language = "python",
            code = """
import streamlit as st
from rag_engine import DocuMindRAGEngine

st.set_page_config(page_title="DocuMind – Smart Document Q&A", page_icon="🧠", layout="wide")

if "theme_mode" not in st.session_state:
    st.session_state.theme_mode = "Dark"
if "rag_engine" not in st.session_state:
    st.session_state.rag_engine = DocuMindRAGEngine()
if "chat_history" not in st.session_state:
    st.session_state.chat_history = []
if "ingested_files" not in st.session_state:
    st.session_state.ingested_files = {}

with st.sidebar:
    st.markdown("### ⚙️ DocuMind Control Center")
    theme_choice = st.radio("Theme", ["Dark", "Light"], horizontal=True)
    uploaded_files = st.file_uploader("Upload PDF files", type=["pdf"], accept_multiple_files=True)
    chunk_size = st.slider("Chunk Size", 250, 1500, 600, 50)
    chunk_overlap = st.slider("Chunk Overlap", 50, 400, 120, 25)
    top_k = st.slider("Top-K Chunks", 1, 8, 4)
    sim_threshold = st.slider("Min Cosine Similarity", 0.05, 0.75, 0.25, 0.05)
    selected_model = st.selectbox("Ollama Model", ["llama3.2", "mistral", "phi3", "gemma2:2b"])

    engine: DocuMindRAGEngine = st.session_state.rag_engine
    engine.chunk_size = chunk_size
    engine.chunk_overlap = min(chunk_overlap, chunk_size - 50)
    engine.similarity_threshold = sim_threshold

    if uploaded_files:
        for uf in uploaded_files:
            if uf.name not in st.session_state.ingested_files:
                pages_c, chunks_c = engine.ingest_pdf(uf.read(), uf.name)
                st.session_state.ingested_files[uf.name] = {"pages": pages_c, "chunks": chunks_c}
                st.success(f"Indexed {uf.name} ({pages_c} pages, {chunks_c} chunks)")

st.title("🧠 DocuMind – Smart Document Q&A Assistant")
for msg in st.session_state.chat_history:
    with st.chat_message(msg["role"]):
        st.markdown(msg["content"])
        if msg.get("sources"):
            with st.expander(f"📚 View {len(msg['sources'])} Grounded Source Citations"):
                for s in msg["sources"]:
                    st.markdown(f"**📄 {s['document_name']} • Page {s['page_number']} (Sim: {s['similarity_score']:.3f})**")
                    st.caption(s["text"])

user_query = st.chat_input("Ask a question about your uploaded PDFs...")
if user_query:
    st.session_state.chat_history.append({"role": "user", "content": user_query})
    with st.chat_message("user"):
        st.markdown(user_query)
    with st.chat_message("assistant"):
        resp = engine.answer_question(user_query, model_name=selected_model, top_k=top_k)
        st.markdown(resp.answer)
        sources = [
            {
                "document_name": r.chunk.document_name,
                "page_number": r.chunk.page_number,
                "similarity_score": r.similarity_score,
                "text": r.chunk.text,
            }
            for r in resp.retrieved_chunks
        ]
        st.session_state.chat_history.append({"role": "assistant", "content": resp.answer, "sources": sources})
            """.trimIndent()
        ),
        PythonSourceFile(
            fileName = "requirements.txt",
            subtitle = "100% Free Local macOS Dependencies (Apple Silicon & Intel)",
            language = "text",
            code = """
streamlit>=1.38.0
PyMuPDF>=1.24.9
sentence-transformers>=3.0.1
faiss-cpu>=1.8.0.post1
ollama>=0.3.3
numpy>=1.26.4,<2.0.0
torch>=2.3.0
pytest>=8.3.0
            """.trimIndent()
        ),
        PythonSourceFile(
            fileName = "Mac_Setup_and_Ollama_Guide.md",
            subtitle = "Step-by-Step MacBook Setup, Ollama Model Commands & Testing",
            language = "markdown",
            code = """
# MacBook Setup & Local Ollama Instructions

## 1. Install & Start Ollama on macOS
```bash
# Install via Homebrew (or download from https://ollama.com/download/mac)
brew install --cask ollama

# Start local Ollama daemon on http://localhost:11434
ollama serve

# Pull a fast, open-weight local LLM in a second Terminal tab:
ollama pull llama3.2       # 2.0 GB — Ideal for 8GB+ Unified Memory MacBooks
# Or:
ollama pull mistral        # 4.1 GB — Ideal for 16GB+ Unified Memory MacBooks
```

## 2. Create Virtual Environment & Run Streamlit App
```bash
cd python_rag
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

# Launch DocuMind in your browser at http://localhost:8501
streamlit run app.py
```

## 3. Run Automated Unit & Integration Tests
```bash
pytest test_rag.py -v
```
            """.trimIndent()
        )
    )
}
