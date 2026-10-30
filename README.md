# 🧠 DocuMind – Smart Document Q&A Assistant

DocuMind is an AI-powered application that helps users upload PDF documents and ask questions about their content using Retrieval-Augmented Generation (RAG).

## 🎯 Project Objective

The goal of this project is to make document understanding easier by using AI to retrieve relevant information and generate answers from uploaded PDFs.

## ✨ Features

- Upload multiple PDF documents.
- Extract text from PDF files.
- Split text into smaller chunks.
- Generate text embeddings.
- Retrieve relevant information using vector search.
- Ask questions about uploaded documents.
- Generate answers using a local AI model.
- Display source references and page numbers.

## 🛠️ Technologies Used

| Technology | Purpose |
|---|---|
| Python | Backend development |
| Streamlit | User interface |
| PyMuPDF | PDF text extraction |
| Sentence Transformers | Text embeddings |
| FAISS | Vector search |
| Ollama | Local AI model execution |
| Llama 3.2 | Answer generation |

## 📂 Project Structure

```text
DocuMind-RAG/
│
├── python_rag/
│   ├── app.py
│   ├── rag_engine.py
│   ├── requirements.txt
│   ├── test_rag.py
│   └── README.md
│
└── app/
    └── Android Companion Application
```

## ⚙️ Installation and Setup

**1. Install the required software**

- Python 3.10–3.12
- Ollama
- VS Code

**2. Download the AI model**

```bash
ollama pull llama3.2:1b
```

**3. Navigate to the project folder**

```bash
cd python_rag
```

**4. Create a virtual environment**

```bash
python3 -m venv .venv
```

**5. Activate the environment**

```bash
source .venv/bin/activate
```

**6. Install dependencies**

```bash
pip install -r requirements.txt
```

## ▶️ Run the Application

Start Ollama and execute:

```bash
streamlit run app.py
```

Open the local application in your browser:

```text
http://localhost:8501
```

## 🧪 Testing

Run the test suite:

```bash
pytest test_rag.py -v
```

## 📚 Learning Outcomes

- Understanding Retrieval-Augmented Generation (RAG).
- PDF processing and text extraction.
- Text chunking and embeddings.
- Vector databases and semantic search.
- Working with Large Language Models (LLMs).
- Building local AI applications using Python.

## 👨‍💻 Developer

**Samson Endeti**

B.Tech – Computer Science and Engineering

GitHub: [
