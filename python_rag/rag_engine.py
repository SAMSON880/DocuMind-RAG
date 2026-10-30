"""
DocuMind – Smart Document Q&A Assistant
Core RAG (Retrieval-Augmented Generation) Engine

Runs 100% locally on macOS (Apple Silicon MPS / CPU) with zero paid APIs:
1. PyMuPDF (fitz) for fast PDF text extraction with exact page numbers.
2. Sentence-aware overlapping text chunker.
3. Sentence-Transformers ('all-MiniLM-L6-v2') for local 384-d embeddings.
4. FAISS (IndexFlatIP with L2 normalization) for exact Cosine Similarity vector search.
5. Local Ollama LLM inference with strict grounding & citation enforcement.
"""

from __future__ import annotations

import io
import logging
import re
from dataclasses import dataclass, field
from typing import Dict, Generator, List, Optional, Tuple

import faiss
import fitz  # PyMuPDF
import numpy as np
import ollama
from sentence_transformers import SentenceTransformer

logger = logging.getLogger("DocuMindRAG")
logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")

NOT_FOUND_RESPONSE = (
    "I could not find relevant information in the uploaded documents to answer this question. "
    "Please verify that the topic is covered in your uploaded PDFs or try rephrasing your query."
)


@dataclass
class PageContent:
    """Represents raw extracted text from a single PDF page."""
    document_name: str
    page_number: int  # 1-indexed page number
    text: str


@dataclass
class DocumentChunk:
    """Represents a single overlapping text chunk linked to its source document and page."""
    chunk_id: int
    document_name: str
    page_number: int
    chunk_index_in_page: int
    text: str
    char_start: int
    char_end: int


@dataclass
class RetrievedChunk:
    """A DocumentChunk paired with its cosine similarity score from FAISS."""
    chunk: DocumentChunk
    similarity_score: float


@dataclass
class RAGResponse:
    """Complete structured answer returned by the DocuMind RAG pipeline."""
    answer: str
    retrieved_chunks: List[RetrievedChunk] = field(default_factory=list)
    is_grounded: bool = True
    model_used: str = ""
    error_message: Optional[str] = None


class DocuMindRAGEngine:
    """
    End-to-End Local RAG Pipeline using PyMuPDF, SentenceTransformers, FAISS, and Ollama.
    """

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

        logger.info("Loading local SentenceTransformer model: %s", embedding_model_name)
        self.embedder = SentenceTransformer(embedding_model_name)
        self.embedding_dim: int = self.embedder.get_sentence_embedding_dimension()

        # FAISS IndexFlatIP computes inner product; on L2-normalized vectors this equals Cosine Similarity.
        self.index: faiss.IndexFlatIP = faiss.IndexFlatIP(self.embedding_dim)
        self.chunks: List[DocumentChunk] = []
        self.indexed_documents: Dict[str, int] = {}  # document_name -> page_count

    # -------------------------------------------------------------------------
    # STAGE 1: PDF Extraction with Page Number Preservation (PyMuPDF)
    # -------------------------------------------------------------------------
    def extract_pages_from_pdf(
        self, pdf_bytes: bytes, document_name: str
    ) -> List[PageContent]:
        """
        Extracts clean text from each page of a PDF file while preserving 1-based page numbers.
        Raises ValueError if the PDF is corrupted, encrypted, or contains only scanned images.
        """
        if not pdf_bytes:
            raise ValueError(f"Uploaded file '{document_name}' is empty.")

        try:
            doc = fitz.open(stream=io.BytesIO(pdf_bytes), filetype="pdf")
        except Exception as exc:
            raise ValueError(
                f"Failed to open '{document_name}' as a valid PDF: {exc}"
            ) from exc

        if doc.is_encrypted:
            doc.close()
            raise ValueError(
                f"PDF '{document_name}' is password-protected. Please upload an unencrypted PDF."
            )

        extracted_pages: List[PageContent] = []
        total_pages = len(doc)

        for page_idx in range(total_pages):
            page = doc.load_page(page_idx)
            raw_text = page.get_text("text") or ""
            cleaned_text = self._normalize_whitespace(raw_text)
            if cleaned_text:
                extracted_pages.append(
                    PageContent(
                        document_name=document_name,
                        page_number=page_idx + 1,  # 1-indexed for human citations
                        text=cleaned_text,
                    )
                )

        doc.close()

        if not extracted_pages:
            raise ValueError(
                f"No selectable text could be extracted from '{document_name}'. "
                "It may be a scanned image-only PDF without an OCR text layer."
            )

        self.indexed_documents[document_name] = total_pages
        return extracted_pages

    @staticmethod
    def _normalize_whitespace(text: str) -> str:
        """Collapses noisy PDF linebreaks and multiple spaces into clean paragraphs."""
        text = text.replace("\x00", "")
        text = re.sub(r"[ \t]+", " ", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    # -------------------------------------------------------------------------
    # STAGE 2: Overlapping Text Chunking
    # -------------------------------------------------------------------------
    def chunk_pages(self, pages: List[PageContent]) -> List[DocumentChunk]:
        """
        Splits each PDF page into overlapping text chunks of target `chunk_size` characters
        with `chunk_overlap` characters carried over between consecutive chunks.
        Snaps to sentence or word boundaries where possible so words are not sliced in half.
        """
        new_chunks: List[DocumentChunk] = []
        next_id = len(self.chunks)

        for page in pages:
            text = page.text
            text_len = len(text)
            start = 0
            chunk_idx_in_page = 0

            while start < text_len:
                end = min(start + self.chunk_size, text_len)

                # If we are not at the end of the page, try to snap to a sentence or word boundary
                if end < text_len:
                    boundary = self._find_best_break(text, start, end)
                    if boundary > start + (self.chunk_size // 2):
                        end = boundary

                snippet = text[start:end].strip()
                if snippet:
                    new_chunks.append(
                        DocumentChunk(
                            chunk_id=next_id,
                            document_name=page.document_name,
                            page_number=page.page_number,
                            chunk_index_in_page=chunk_idx_in_page,
                            text=snippet,
                            char_start=start,
                            char_end=end,
                        )
                    )
                    next_id += 1
                    chunk_idx_in_page += 1

                if end >= text_len:
                    break

                # Advance by (chunk length - overlap), ensuring forward progress
                step = max(1, (end - start) - self.chunk_overlap)
                start += step

        return new_chunks

    @staticmethod
    def _find_best_break(text: str, start: int, max_end: int) -> int:
        """Finds the rightmost sentence-ending punctuation or space before max_end."""
        window = text[start:max_end]
        for pattern in [r"\.\s", r"\!\s", r"\?\s", r"\n", r"\s"]:
            matches = list(re.finditer(pattern, window))
            if matches:
                last_match = matches[-1]
                return start + last_match.end()
        return max_end

    # -------------------------------------------------------------------------
    # STAGE 3 & 4: Local Sentence-Transformer Embeddings & FAISS Vector Storage
    # -------------------------------------------------------------------------
    def index_chunks(self, new_chunks: List[DocumentChunk]) -> int:
        """
        Computes local embeddings using SentenceTransformers, normalizes them to unit length,
        and appends them to the in-memory FAISS IndexFlatIP vector database.
        """
        if not new_chunks:
            return 0

        texts = [c.text for c in new_chunks]
        embeddings = self.embedder.encode(
            texts,
            batch_size=32,
            show_progress_bar=False,
            convert_to_numpy=True,
            normalize_embeddings=True,
        ).astype("float32")

        self.index.add(embeddings)
        self.chunks.extend(new_chunks)
        logger.info(
            "Indexed %d new chunks into FAISS (Total chunks: %d)",
            len(new_chunks),
            len(self.chunks),
        )
        return len(new_chunks)

    def ingest_pdf(self, pdf_bytes: bytes, document_name: str) -> Tuple[int, int]:
        """
        Convenience method executing Stage 1 (Extract) -> Stage 2 (Chunk) -> Stage 3/4 (Embed & Index).
        Returns (num_pages_extracted, num_chunks_indexed).
        """
        # Avoid duplicate ingestion of the same filename
        if document_name in self.indexed_documents:
            self.remove_document(document_name)

        pages = self.extract_pages_from_pdf(pdf_bytes, document_name)
        new_chunks = self.chunk_pages(pages)
        self.index_chunks(new_chunks)
        return len(pages), len(new_chunks)

    def remove_document(self, document_name: str) -> None:
        """Removes a document's chunks and rebuilds the FAISS index."""
        remaining = [c for c in self.chunks if c.document_name != document_name]
        self.indexed_documents.pop(document_name, None)
        self.chunks = []
        self.index.reset()
        for idx, chunk in enumerate(remaining):
            chunk.chunk_id = idx
        if remaining:
            self.index_chunks(remaining)

    def clear_all(self) -> None:
        """Resets all indexed documents, chunks, and FAISS vectors."""
        self.chunks.clear()
        self.indexed_documents.clear()
        self.index.reset()

    # -------------------------------------------------------------------------
    # STAGE 5: Semantic Vector Retrieval from FAISS
    # -------------------------------------------------------------------------
    def retrieve(
        self, query: str, top_k: int = 4, threshold: Optional[float] = None
    ) -> List[RetrievedChunk]:
        """
        Embeds the user's question into the same 384-d vector space and searches FAISS
        for the top_k most relevant chunks above `similarity_threshold`.
        """
        if not query.strip() or self.index.ntotal == 0:
            return []

        min_score = self.similarity_threshold if threshold is None else threshold

        query_vec = self.embedder.encode(
            [query.strip()],
            convert_to_numpy=True,
            normalize_embeddings=True,
        ).astype("float32")

        k = min(top_k, self.index.ntotal)
        scores, indices = self.index.search(query_vec, k)

        results: List[RetrievedChunk] = []
        for score, idx in zip(scores[0], indices[0]):
            if idx < 0 or idx >= len(self.chunks):
                continue
            if float(score) >= min_score:
                results.append(
                    RetrievedChunk(
                        chunk=self.chunks[idx],
                        similarity_score=float(score),
                    )
                )

        return results

    # -------------------------------------------------------------------------
    # STAGE 6 & 7: Grounded Prompt Construction & Local Ollama Inference
    # -------------------------------------------------------------------------
    @staticmethod
    def build_grounded_prompt(
        question: str, retrieved_chunks: List[RetrievedChunk]
    ) -> Tuple[str, str]:
        """
        Constructs a strict system prompt and formatted context block containing document
        names and page numbers so the local LLM answers ONLY from retrieved context.
        """
        system_prompt = (
            "You are DocuMind, a precise and trustworthy Document Q&A Assistant.\n"
            "CRITICAL RULES:\n"
            "1. Answer the user's question ONLY using the facts explicitly stated in the "
            "PROVIDED DOCUMENT CONTEXT below.\n"
            "2. Do NOT use outside knowledge, assumptions, or extrapolation.\n"
            "3. Every factual claim MUST include an inline source citation in the format "
            "[Source: <Document Name>, Page <Page Number>].\n"
            "4. If the PROVIDED DOCUMENT CONTEXT does not contain enough information to "
            "answer the question, reply with this exact sentence:\n"
            f'"{NOT_FOUND_RESPONSE}"'
        )

        context_blocks: List[str] = []
        for rank, item in enumerate(retrieved_chunks, start=1):
            c = item.chunk
            context_blocks.append(
                f"--- CHUNK {rank} | Document: {c.document_name} | Page: {c.page_number} "
                f"| Similarity: {item.similarity_score:.3f} ---\n{c.text}"
            )

        formatted_context = "\n\n".join(context_blocks)
        user_prompt = (
            f"PROVIDED DOCUMENT CONTEXT:\n{formatted_context}\n\n"
            f"USER QUESTION:\n{question.strip()}\n\n"
            "GROUNDED ANSWER (with [Source: Document, Page X] citations):"
        )
        return system_prompt, user_prompt

    def check_ollama_status(self) -> Tuple[bool, List[str], str]:
        """
        Checks whether the local Ollama daemon is running and returns installed model names.
        Returns (is_running, model_list, status_message).
        """
        try:
            client = ollama.Client(host=self.ollama_host)
            response = client.list()
            models_raw = response.get("models", []) if isinstance(response, dict) else getattr(response, "models", [])
            model_names: List[str] = []
            for m in models_raw:
                name = m.get("name") or m.get("model") if isinstance(m, dict) else getattr(m, "model", None) or getattr(m, "name", None)
                if name:
                    model_names.append(str(name))
            return True, model_names, f"Connected to Ollama at {self.ollama_host}"
        except Exception as exc:
            return (
                False,
                [],
                f"Cannot reach Ollama at {self.ollama_host}. Make sure Ollama is running (`ollama serve`). Error: {exc}",
            )

    def answer_question(
        self,
        question: str,
        model_name: str = "llama3.2",
        top_k: int = 4,
        temperature: float = 0.1,
    ) -> RAGResponse:
        """
        Executes the complete RAG query flow:
        Retrieve top-k chunks -> Verify grounding threshold -> Call local Ollama model.
        """
        if self.index.ntotal == 0:
            return RAGResponse(
                answer="Please upload and index at least one PDF document before asking questions.",
                retrieved_chunks=[],
                is_grounded=False,
                model_used=model_name,
            )

        retrieved = self.retrieve(question, top_k=top_k)

        # Requirement 10: Show an appropriate response when information is not found
        if not retrieved:
            return RAGResponse(
                answer=NOT_FOUND_RESPONSE,
                retrieved_chunks=[],
                is_grounded=False,
                model_used=model_name,
            )

        system_prompt, user_prompt = self.build_grounded_prompt(question, retrieved)

        try:
            client = ollama.Client(host=self.ollama_host)
            response = client.chat(
                model=model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                options={"temperature": temperature},
            )
            content = (
                response["message"]["content"]
                if isinstance(response, dict)
                else response.message.content
            )
            return RAGResponse(
                answer=content.strip(),
                retrieved_chunks=retrieved,
                is_grounded=NOT_FOUND_RESPONSE not in content,
                model_used=model_name,
            )
        except Exception as exc:
            error_msg = (
                f"Ollama Inference Error ({ exc }). "
                f"Ensure Ollama is running (`ollama serve`) and model '{model_name}' is pulled (`ollama pull {model_name}`)."
            )
            return RAGResponse(
                answer=error_msg,
                retrieved_chunks=retrieved,
                is_grounded=False,
                model_used=model_name,
                error_message=error_msg,
            )

    def stream_answer(
        self,
        question: str,
        model_name: str = "llama3.2",
        top_k: int = 4,
        temperature: float = 0.1,
    ) -> Tuple[List[RetrievedChunk], Generator[str, None, None]]:
        """
        Streams tokens from the local Ollama model in real time while returning the
        retrieved FAISS source chunks immediately for UI citation display.
        """
        retrieved = self.retrieve(question, top_k=top_k)

        def _empty_gen() -> Generator[str, None, None]:
            yield NOT_FOUND_RESPONSE

        if not retrieved:
            return [], _empty_gen()

        system_prompt, user_prompt = self.build_grounded_prompt(question, retrieved)

        def _token_generator() -> Generator[str, None, None]:
            try:
                client = ollama.Client(host=self.ollama_host)
                stream = client.chat(
                    model=model_name,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    options={"temperature": temperature},
                    stream=True,
                )
                for chunk in stream:
                    token = (
                        chunk["message"]["content"]
                        if isinstance(chunk, dict)
                        else chunk.message.content
                    )
                    if token:
                        yield token
            except Exception as exc:
                yield (
                    f"\n\n**[Ollama Connection Error]**: Could not run inference with model `{model_name}` "
                    f"at `{self.ollama_host}`.\n"
                    f"- Run `ollama serve` in your Mac terminal.\n"
                    f"- Pull the model with `ollama pull {model_name}`.\n"
                    f"- Details: `{exc}`"
                )

        return retrieved, _token_generator()
