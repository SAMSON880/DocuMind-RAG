# 🧠 DocuMind – Smart Document Q&A Assistant (Python Mac RAG + Android Companion)

This repository contains two complete, 100% local, zero-billing implementations of **DocuMind – Smart Document Q&A Assistant**:

1. **`/python_rag/` (MacBook Python + Streamlit + PyMuPDF + SentenceTransformers + FAISS + Ollama)**:
   - `python_rag/app.py`: Full Streamlit web interface with multi-PDF upload, chat history, source citations, chunk/vector inspector, and Light/Dark styling.
   - `python_rag/rag_engine.py`: Complete 7-stage local RAG engine using `PyMuPDF (fitz)`, sentence-aware overlapping chunking, `sentence-transformers` (`all-MiniLM-L6-v2`), `faiss-cpu` (`IndexFlatIP`), and `ollama`.
   - `python_rag/requirements.txt`: Exact Python dependencies for macOS.
   - `python_rag/test_rag.py`: Automated `pytest` suite verifying extraction, chunking, FAISS search, and out-of-context guardrails.
   - `python_rag/README.md`: Detailed Mac setup instructions, Ollama setup guide, and Beginner AI Engineer walkthrough.

2. **`/app/` (Native Android Companion App for Live Preview & Local RAG Testing)**:
   - Runs directly in the AI Studio Android Emulator with real PDF upload & page-preserving extraction, overlapping text chunking, local 384-d vector indexing in Room SQLite, live Ollama HTTP client + grounded extractive fallback, interactive RAG Pipeline Visualizer for beginner AI Engineers, and a built-in **Python & Mac Code Studio** tab where you can inspect and copy every Python file!
