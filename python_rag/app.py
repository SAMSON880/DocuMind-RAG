"""
DocuMind – Smart Document Q&A Assistant
Streamlit Web Application (100% Local on macOS)

Run with:
    streamlit run app.py
"""

from __future__ import annotations

from typing import Any, Dict, List
import streamlit as st

from rag_engine import DocuMindRAGEngine, NOT_FOUND_RESPONSE, RetrievedChunk


# -----------------------------------------------------------------------------
# Page Configuration
# -----------------------------------------------------------------------------
st.set_page_config(
    page_title="DocuMind – Smart Document Q&A Assistant",
    page_icon="🧠",
    layout="wide",
    initial_sidebar_state="expanded",
)


# -----------------------------------------------------------------------------
# Session State Initialization
# -----------------------------------------------------------------------------
if "theme_mode" not in st.session_state:
    st.session_state.theme_mode = "Dark"

if "rag_engine" not in st.session_state:
    st.session_state.rag_engine = DocuMindRAGEngine(
        embedding_model_name="all-MiniLM-L6-v2",
        chunk_size=600,
        chunk_overlap=120,
        similarity_threshold=0.25,
    )

if "chat_history" not in st.session_state:
    # Each message: {"role": "user"|"assistant", "content": str, "sources": List[Dict]}
    st.session_state.chat_history: List[Dict[str, Any]] = []

if "ingested_files" not in st.session_state:
    st.session_state.ingested_files: Dict[str, Dict[str, int]] = {}


# -----------------------------------------------------------------------------
# Dynamic Light / Dark CSS Styling
# -----------------------------------------------------------------------------
def apply_custom_theme(mode: str) -> None:
    is_dark = mode == "Dark"
    bg_color = "#0B132B" if is_dark else "#F8FAFC"
    surface_color = "#1C2541" if is_dark else "#FFFFFF"
    card_border = "#2D3A5E" if is_dark else "#E2E8F0"
    text_primary = "#F1F5F9" if is_dark else "#0F172A"
    text_secondary = "#94A3B8" if is_dark else "#475569"
    accent_color = "#00B4D8" if is_dark else "#0284C7"
    badge_bg = "rgba(0, 180, 216, 0.15)" if is_dark else "rgba(2, 132, 199, 0.1)"

    st.markdown(
        f"""
        <style>
        .stApp {{
            background-color: {bg_color};
            color: {text_primary};
        }}
        [data-testid="stSidebar"] {{
            background-color: {surface_color};
            border-right: 1px solid {card_border};
        }}
        .documind-header {{
            padding: 1.25rem 1.5rem;
            border-radius: 14px;
            background: linear-gradient(135deg, {surface_color} 0%, {bg_color} 100%);
            border: 1px solid {card_border};
            margin-bottom: 1.25rem;
        }}
        .documind-title {{
            font-size: 1.75rem;
            font-weight: 800;
            color: {text_primary};
            margin: 0;
        }}
        .documind-subtitle {{
            font-size: 0.95rem;
            color: {text_secondary};
            margin-top: 0.35rem;
        }}
        .source-card {{
            background-color: {surface_color};
            border: 1px solid {card_border};
            border-left: 4px solid {accent_color};
            border-radius: 10px;
            padding: 0.85rem 1rem;
            margin-top: 0.5rem;
            font-size: 0.88rem;
            color: {text_primary};
        }}
        .source-badge {{
            display: inline-block;
            background: {badge_bg};
            color: {accent_color};
            font-weight: 600;
            padding: 0.15rem 0.55rem;
            border-radius: 6px;
            font-size: 0.78rem;
            margin-right: 0.5rem;
        }}
        .metric-pill {{
            background-color: {surface_color};
            border: 1px solid {card_border};
            border-radius: 10px;
            padding: 0.75rem;
            text-align: center;
        }}
        </style>
        """,
        unsafe_allow_html=True,
    )


# -----------------------------------------------------------------------------
# Sidebar Controls
# -----------------------------------------------------------------------------
with st.sidebar:
    st.markdown("### ⚙️ DocuMind Control Center")

    theme_choice = st.radio(
        "Interface Theme",
        options=["Dark", "Light"],
        index=0 if st.session_state.theme_mode == "Dark" else 1,
        horizontal=True,
    )
    if theme_choice != st.session_state.theme_mode:
        st.session_state.theme_mode = theme_choice
        st.rerun()

    apply_custom_theme(st.session_state.theme_mode)

    st.divider()
    st.markdown("#### 📄 1. Upload PDF Documents")
    uploaded_files = st.file_uploader(
        "Drop one or more PDF files here",
        type=["pdf"],
        accept_multiple_files=True,
        help="Text is extracted per page locally using PyMuPDF.",
    )

    with st.expander("🔧 Chunking & Vector Settings", expanded=False):
        chunk_size = st.slider(
            "Chunk Size (characters)", min_value=250, max_value=1500, value=600, step=50
        )
        chunk_overlap = st.slider(
            "Chunk Overlap (characters)", min_value=50, max_value=400, value=120, step=25
        )
        top_k = st.slider("Top-K Retrieved Chunks", min_value=1, max_value=8, value=4)
        similarity_threshold = st.slider(
            "Min Cosine Similarity Threshold",
            min_value=0.05,
            max_value=0.75,
            value=0.25,
            step=0.05,
            help="Chunks scoring below this threshold are filtered out to prevent hallucination.",
        )

    engine: DocuMindRAGEngine = st.session_state.rag_engine
    engine.chunk_size = chunk_size
    engine.chunk_overlap = min(chunk_overlap, chunk_size - 50)
    engine.similarity_threshold = similarity_threshold

    if uploaded_files:
        for uf in uploaded_files:
            if uf.name not in st.session_state.ingested_files:
                with st.spinner(f"Extracting & indexing '{uf.name}'..."):
                    try:
                        pdf_bytes = uf.read()
                        pages_count, chunks_count = engine.ingest_pdf(pdf_bytes, uf.name)
                        st.session_state.ingested_files[uf.name] = {
                            "pages": pages_count,
                            "chunks": chunks_count,
                        }
                        st.success(
                            f"Indexed **{uf.name}** ({pages_count} pages, {chunks_count} chunks)"
                        )
                    except Exception as exc:
                        st.error(str(exc))

    if st.session_state.ingested_files:
        st.markdown("##### Indexed Knowledge Base")
        for doc_name, stats in list(st.session_state.ingested_files.items()):
            col1, col2 = st.columns([4, 1])
            col1.caption(f"📕 **{doc_name}** — {stats['pages']}p • {stats['chunks']} chunks")
            if col2.button("✖", key=f"del_{doc_name}", help=f"Remove {doc_name}"):
                engine.remove_document(doc_name)
                del st.session_state.ingested_files[doc_name]
                st.rerun()

    st.divider()
    st.markdown("#### 🦙 2. Local Ollama LLM")
    ollama_host = st.text_input("Ollama Host URL", value=engine.ollama_host)
    engine.ollama_host = ollama_host

    is_online, installed_models, status_msg = engine.check_ollama_status()
    if is_online:
        st.success(f"Ollama Online ({len(installed_models)} models)")
    else:
        st.warning("Ollama daemon not detected. Start with `ollama serve`.")

    default_models = installed_models if installed_models else ["llama3.2", "mistral", "phi3", "gemma2:2b"]
    selected_model = st.selectbox("Select Local Ollama Model", options=default_models)
    temperature = st.slider("LLM Temperature", min_value=0.0, max_value=1.0, value=0.1, step=0.05)

    col_clear1, col_clear2 = st.columns(2)
    if col_clear1.button("🗑️ Clear Chat", use_container_width=True):
        st.session_state.chat_history.clear()
        st.rerun()
    if col_clear2.button("♻️ Reset Index", use_container_width=True):
        engine.clear_all()
        st.session_state.ingested_files.clear()
        st.session_state.chat_history.clear()
        st.rerun()


# -----------------------------------------------------------------------------
# Main Workspace Header & Metrics
# -----------------------------------------------------------------------------
st.markdown(
    """
    <div class="documind-header">
        <p class="documind-title">🧠 DocuMind – Smart Document Q&A Assistant</p>
        <p class="documind-subtitle">
            100% Local Retrieval-Augmented Generation • PyMuPDF + SentenceTransformers + FAISS + Ollama
        </p>
    </div>
    """,
    unsafe_allow_html=True,
)

m_col1, m_col2, m_col3, m_col4 = st.columns(4)
m_col1.metric("Indexed Documents", len(st.session_state.ingested_files))
m_col2.metric("FAISS Vector Chunks", engine.index.ntotal)
m_col3.metric("Embedding Dimension", f"{engine.embedding_dim}-d (MiniLM)")
m_col4.metric("Active Local LLM", selected_model)

tab_chat, tab_inspector = st.tabs(["💬 Grounded Document Chat", "🔍 Chunk & FAISS Vector Inspector"])

# -----------------------------------------------------------------------------
# TAB 1: Grounded Document Chat
# -----------------------------------------------------------------------------
with tab_chat:
    if not st.session_state.ingested_files:
        st.info(
            "👈 **Upload one or more PDF documents in the sidebar** to build your local FAISS vector index and start asking questions."
        )

    # Render previous chat messages
    for msg in st.session_state.chat_history:
        with st.chat_message(msg["role"]):
            st.markdown(msg["content"])
            if msg.get("sources"):
                with st.expander(f"📚 View {len(msg['sources'])} Grounded Source Citations"):
                    for src in msg["sources"]:
                        st.markdown(
                            f"""
                            <div class="source-card">
                                <span class="source-badge">📄 {src['document_name']}</span>
                                <span class="source-badge">📍 Page {src['page_number']}</span>
                                <span class="source-badge">🎯 Cosine Sim: {src['similarity_score']:.3f}</span>
                                <div style="margin-top: 0.45rem;">{src['text']}</div>
                            </div>
                            """,
                            unsafe_allow_html=True,
                        )

    # Chat Input
    user_query = st.chat_input("Ask a specific question about your uploaded PDFs...")
    if user_query:
        st.session_state.chat_history.append({"role": "user", "content": user_query})
        with st.chat_message("user"):
            st.markdown(user_query)

        with st.chat_message("assistant"):
            if engine.index.ntotal == 0:
                warning_msg = "Please upload at least one PDF document in the sidebar first."
                st.warning(warning_msg)
                st.session_state.chat_history.append(
                    {"role": "assistant", "content": warning_msg, "sources": []}
                )
            else:
                retrieved_chunks, token_stream = engine.stream_answer(
                    question=user_query,
                    model_name=selected_model,
                    top_k=top_k,
                    temperature=temperature,
                )

                full_answer = st.write_stream(token_stream)

                serialized_sources = [
                    {
                        "document_name": item.chunk.document_name,
                        "page_number": item.chunk.page_number,
                        "similarity_score": item.similarity_score,
                        "text": item.chunk.text,
                    }
                    for item in retrieved_chunks
                ]

                if serialized_sources:
                    with st.expander(
                        f"📚 View {len(serialized_sources)} Grounded Source Citations",
                        expanded=True,
                    ):
                        for src in serialized_sources:
                            st.markdown(
                                f"""
                                <div class="source-card">
                                    <span class="source-badge">📄 {src['document_name']}</span>
                                    <span class="source-badge">📍 Page {src['page_number']}</span>
                                    <span class="source-badge">🎯 Cosine Sim: {src['similarity_score']:.3f}</span>
                                    <div style="margin-top: 0.45rem;">{src['text']}</div>
                                </div>
                                """,
                                unsafe_allow_html=True,
                            )

                st.session_state.chat_history.append(
                    {
                        "role": "assistant",
                        "content": str(full_answer),
                        "sources": serialized_sources,
                    }
                )

# -----------------------------------------------------------------------------
# TAB 2: Chunk & FAISS Vector Inspector
# -----------------------------------------------------------------------------
with tab_inspector:
    st.subheader("Indexed Overlapping Chunks & Page Metadata")
    if not engine.chunks:
        st.caption("No chunks indexed yet. Upload a PDF to inspect how text is split and embedded.")
    else:
        filter_doc = st.selectbox(
            "Filter by Document",
            options=["All Documents"] + list(st.session_state.ingested_files.keys()),
        )
        visible_chunks = [
            c
            for c in engine.chunks
            if filter_doc == "All Documents" or c.document_name == filter_doc
        ]
        st.write(f"Showing **{len(visible_chunks)}** chunks:")
        for c in visible_chunks[:100]:
            with st.expander(
                f"Chunk #{c.chunk_id} • {c.document_name} (Page {c.page_number}) • Chars {c.char_start}–{c.char_end}"
            ):
                st.write(c.text)
