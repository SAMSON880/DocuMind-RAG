# 🧠 DocuMind – Smart Document Q&A Assistant

**DocuMind** is a 100% local, privacy-first **Retrieval-Augmented Generation (RAG)** application designed to run directly on your **MacBook** (Apple Silicon M1/M2/M3/M4 or Intel) without paid APIs, subscriptions, cloud billing, or external data sharing.

---

## 🏗️ Technology Stack

| Layer | Technology | Purpose |
| :--- | :--- | :--- |
| **Frontend UI** | **Streamlit** | Upload PDFs, inspect chunks/vectors, chat with history & citations, Light/Dark themes |
| **PDF Parsing** | **PyMuPDF (`fitz`)** | High-speed local PDF text extraction with exact 1-based page number preservation |
| **Chunking** | **Custom Overlapping Chunker** | Sentence-aware sliding window splitter (`chunk_size=600`, `chunk_overlap=120`) |
| **Embeddings** | **Sentence-Transformers** | Local `all-MiniLM-L6-v2` (384-d unit-normalized dense vectors on CPU / Apple MPS) |
| **Vector Store** | **FAISS (`faiss-cpu`)** | `IndexFlatIP` exact Inner Product / Cosine Similarity search with threshold filtering |
| **Local LLM** | **Ollama** | Local inference (`llama3.2`, `mistral`, `phi3`, `gemma2:2b`) grounded in retrieved chunks |

---

## 📂 Project Structure

```text
python_rag/
├── app.py              # Complete Streamlit web interface (upload, chat, source cards, light/dark toggle)
├── rag_engine.py       # Modular 7-stage RAG pipeline (PyMuPDF, Chunker, SentenceTransformers, FAISS, Ollama)
├── test_rag.py         # Automated pytest suite verifying extraction, chunking, FAISS search & guardrails
├── requirements.txt    # Local Python dependencies for macOS
└── README.md           # Mac setup instructions, Ollama guide, and beginner AI Engineer walkthrough
```

*(Note: This workspace also includes the native Android companion app in `app/` for live testing in the AI Studio Android Emulator, with a built-in Python Code Studio tab!)*

---

## 💻 Step-by-Step Setup Instructions for MacBook

### Step 1: Install & Run Ollama Locally on macOS

1. **Install Ollama** (via Homebrew or official macOS app):
   ```bash
   brew install --cask ollama
   ```
   *(Or download directly from [https://ollama.com/download/mac](https://ollama.com/download/mac) and drag `Ollama.app` to Applications).*

2. **Start the Ollama Local Server**:
   If you launched `Ollama.app`, the background daemon runs automatically on `http://localhost:11434`. Or run in a terminal:
   ```bash
   ollama serve
   ```

3. **Download a Compatible Local Model**:
   Open a new Terminal tab and pull one of these lightweight, high-accuracy open-weight models suitable for MacBooks:
   - **Recommended for 8 GB+ RAM MacBooks (2.0 GB download)**:
     ```bash
     ollama pull llama3.2
     ```
   - **Alternative Ultra-Fast Model (1.3 GB download)**:
     ```bash
     ollama pull llama3.2:1b
     ```
   - **Alternative 7B Model for 16 GB+ RAM MacBooks (4.1 GB download)**:
     ```bash
     ollama pull mistral
     ```

4. **Verify Ollama is Working**:
   ```bash
   ollama list
   ```

---

### Step 2: Set Up the Python Environment on macOS

1. **Navigate to the `python_rag` folder**:
   ```bash
   cd python_rag
   ```

2. **Create and Activate a Python Virtual Environment** (Python 3.10 – 3.12 recommended):
   ```bash
   python3 -m venv .venv
   source .venv/bin/activate
   ```

3. **Upgrade `pip` and Install Dependencies**:
   ```bash
   pip install --upgrade pip
   pip install -r requirements.txt
   ```
   *On first run, `sentence-transformers` will automatically cache the open-source `all-MiniLM-L6-v2` (~80 MB) model to `~/.cache/huggingface/` for 100% offline reuse.*

4. **Launch DocuMind**:
   ```bash
   streamlit run app.py
   ```
   Your default browser will automatically open `http://localhost:8501`.

---

## 🧪 Testing & Error Handling Verification

### Run the Automated Unit Test Suite
```bash
pytest test_rag.py -v
```
This test suite dynamically creates a multi-page PDF in memory via PyMuPDF and verifies:
1. **Page-Preserving Extraction**: Ensures Page 1 and Page 2 text are accurately attributed.
2. **Overlapping Chunking & FAISS Search**: Confirms that queries retrieve the exact matching page and document.
3. **Out-of-Context Guardrail**: Confirms that unrelated questions (e.g., baking a soufflé when a technical spec PDF is uploaded) are blocked by the similarity threshold and return the explicit *"not found"* message.
4. **Corrupt / Empty PDF Handling**: Confirms graceful `ValueError` exceptions for empty, corrupt, or encrypted PDFs.

### Built-In Error Handling in `DocuMind`
- **Encrypted / Password-Protected PDFs**: Detects `doc.is_encrypted` and alerts the user cleanly.
- **Scanned Image-Only PDFs**: Detects when zero selectable characters exist across pages and explains that an OCR text layer is required.
- **Ollama Daemon Offline / Model Not Pulled**: Checks `client.list()` on startup and catches inference exceptions gracefully with exact `ollama serve` and `ollama pull <model>` recovery commands.
- **Hallucination / Out-of-Domain Guardrail**: Uses both a FAISS cosine similarity cutoff (`similarity_threshold = 0.25`) and a strict system prompt so unanswered topics return:
  > *"I could not find relevant information in the uploaded documents to answer this question. Please verify that the topic is covered in your uploaded PDFs or try rephrasing your query."*

---

## 🎓 Beginner AI Engineer Guide: How Each Stage of RAG Works

**Retrieval-Augmented Generation (RAG)** solves two big limitations of standalone LLMs:
1. LLMs don't know about your private PDF files.
2. LLMs can "hallucinate" (make up plausible-sounding wrong answers) when guessing from memory.

Instead of retraining a model, RAG turns every question into an **open-book exam**:

```text
[PDF Files] ──► (1. PyMuPDF Extract) ──► (2. Overlapping Chunker) ──► (3. SentenceTransformer) ──► (4. FAISS Index)
                                                                                                         │
[User Question] ───────────────────────► (5. Query Embedding) ──────► (6. Top-K Cosine Search) ◄─────────┘
                                                                                 │
[Grounded Answer + Page Citations] ◄──── (8. Local Ollama LLM) ◄──── (7. Prompt + Retrieved Context)
```

### Stage 1: PDF Extraction with Page Preservation (`extract_pages_from_pdf`)
- **What happens**: PyMuPDF (`fitz.open`) iterates through every page (`0` to `len(doc)-1`), extracts raw text via `page.get_text("text")`, normalizes noisy whitespace, and attaches `page_number = page_idx + 1`.
- **Why it matters**: If you concatenate the entire PDF into one giant string before chunking, you lose track of which page a paragraph came from! Preserving `PageContent(document_name, page_number, text)` guarantees accurate page citations at the end.

### Stage 2: Overlapping Text Chunking (`chunk_pages`)
- **What happens**: Each page's text is sliced into windows of `600` characters (`chunk_size`) with a `120`-character overlap (`chunk_overlap`), snapping to the nearest sentence boundary (`.`, `!`, `?`).
- **Why overlap is essential**: Suppose a key sentence says *"The server reboot code is 9942-ALPHA."* If a hard slice cuts right after *"The server reboot code is"* at the end of Chunk 1 and puts *"9942-ALPHA"* at the start of Chunk 2, neither chunk alone holds the complete fact. A 120-character sliding overlap ensures boundary sentences stay intact in at least one chunk.

### Stage 3: Local Dense Embeddings (`SentenceTransformer("all-MiniLM-L6-v2")`)
- **What happens**: Each text chunk is passed through a local Transformer encoder that outputs a **384-dimensional vector** of floating-point numbers (`float32`), normalized to unit length (`L2 norm = 1.0`).
- **Why it beats keyword search**: Traditional `Ctrl+F` keyword search fails if a PDF says *"macOS memory allocation"* and the user asks *"How much RAM does the Mac use?"*. Dense embeddings map semantically similar concepts to nearby points in 384-dimensional space even when the exact words differ.

### Stage 4 & 5: FAISS Vector Indexing & Retrieval (`faiss.IndexFlatIP`)
- **What happens**: We store all 384-d unit vectors in `faiss.IndexFlatIP(384)`. When the user asks a question, we embed the question into a 384-d unit vector $\mathbf{q}$ and compute the **dot product** $\mathbf{q} \cdot \mathbf{c}_i$ against every chunk $\mathbf{c}_i$.
- **The Math**: Because both vectors have length $1.0$, their dot product equals their **Cosine Similarity**:
  $$\cos(\theta) = \frac{\mathbf{q} \cdot \mathbf{c}}{\|\mathbf{q}\| \|\mathbf{c}\|} = \mathbf{q} \cdot \mathbf{c} \in [-1, 1]$$
  FAISS returns the `top_k` highest-scoring chunks in sub-millisecond time, and we filter out any chunk below `similarity_threshold` (default `0.25`).

### Stage 6 & 7: Grounded Prompt Construction & Local Ollama Inference (`build_grounded_prompt` & `stream_answer`)
- **What happens**: If `retrieved_chunks` is empty, we immediately return `NOT_FOUND_RESPONSE` without even calling the LLM—saving compute and guaranteeing zero hallucination. Otherwise, we format the top chunks with explicit headers:
  ```text
  --- CHUNK 1 | Document: policy.pdf | Page: 2 | Similarity: 0.684 ---
  ```
  and send them to your local Ollama server (`http://localhost:11434`) with `temperature=0.1` (low randomness for factual accuracy).
