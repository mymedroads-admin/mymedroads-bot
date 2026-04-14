# MyMedRoads Bot

A conversational AI assistant for [MyMedRoads](https://uat.mymedroads.com) — a platform that helps people find hospitals, plan medical travel, and arrange support services.

Built with **Spring Boot 3.3**, **Claude (Anthropic)** for chat, and **RAG (Retrieval-Augmented Generation)** using **Ollama embeddings** + **PGVector** for domain-specific knowledge retrieval.

---

## Architecture

```
User query
    │
    ▼
Embed query (Ollama: nomic-embed-text)
    │
    ▼
Similarity search → PGVector (top 3 chunks)
    │
    ▼
Inject chunks into system prompt
    │
    ▼
Claude API (claude-sonnet-4-6) → Response
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.3 (WAR, external Tomcat 10) |
| Chat / Generation | Anthropic Claude (`claude-sonnet-4-6`) |
| Embeddings | Ollama (`nomic-embed-text`) — local, free |
| Vector Store | PGVector (PostgreSQL extension) |
| RAG Framework | Spring AI 1.0 |
| Logging | Log4j2 |
| Build | Maven |
| Java | 21 |

---

## Prerequisites

- Java 21
- Maven 3.8+
- Docker (for PGVector)
- [Ollama](https://ollama.com) with `nomic-embed-text` model
- Anthropic API key

---

## Local Setup

### 1. Start PGVector

```bash
docker run -d --name pgvector \
  -e POSTGRES_DB=mymedroads \
  -e POSTGRES_USER=bot \
  -e POSTGRES_PASSWORD=secret \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

### 2. Start Ollama with embedding model

```bash
# Install Ollama: https://ollama.com
ollama pull nomic-embed-text
ollama serve   # starts on http://localhost:11434
```

### 3. Set environment variables

```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxx
```

### 4. Run the application

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

> For Tomcat deployment, build `mvn package` and deploy `target/mymedroads-bot.war`.  
> The context path becomes `/mymedroads-bot` automatically from the WAR filename.

---

## Configuration

All configuration is in [`src/main/resources/application.yml`](src/main/resources/application.yml).

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port (set to `443` for production) |
| `anthropic.api-key` | `${ANTHROPIC_API_KEY}` | Anthropic API key |
| `anthropic.model` | `claude-sonnet-4-6` | Claude model |
| `anthropic.max-tokens` | `4096` | Max response tokens |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `mymedroads` | Database name |
| `DB_USER` | `bot` | Database username |
| `DB_PASSWORD` | `secret` | Database password |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |

---

## API Endpoints

### Chat

**POST** `/conversations/chat`

Send a message. Omit `sessionId` to start a new conversation.

```json
// Request
{
  "message": "Which hospitals in Bangkok offer cardiac surgery?",
  "sessionId": "optional-session-id"
}

// Response
{
  "sessionId": "abc123",
  "message": "Bangkok has several internationally accredited hospitals...",
  "model": "claude-sonnet-4-6",
  "inputTokens": 512,
  "outputTokens": 180
}
```

### Session Management

**POST** `/conversations/session/new` — Create a new session explicitly

**DELETE** `/conversations/session/{sessionId}` — Clear a session and its history

### Knowledge Ingestion (RAG)

**POST** `/conversations/admin/ingest/documents` — Ingest all TXT files from `knowledge/`

**POST** `/conversations/admin/ingest/url` — Crawl and ingest a URL

```json
// Request body
{ "url": "https://uat.mymedroads.com/hospitals" }
```

**POST** `/conversations/admin/ingest/upload` — Upload a document with a description (`multipart/form-data`)

| Field | Type | Description |
|---|---|---|
| `file` | file part | Plain-text document to ingest |
| `description` | text part | Brief summary of what the document contains |

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/upload \
  -F "file=@/path/to/document.txt" \
  -F "description=MyMedRoads hospital pricing guide Q1 2026"
```

```json
// Response
{ "status": "complete", "filename": "document.txt", "description": "...", "chunksIngested": 14 }
```

**DELETE** `/conversations/admin/ingest/remove` — Remove all chunks for a source (filename or URL)

```json
// Request body — use the exact filename or URL that was ingested
{ "source": "document.txt" }
{ "source": "https://uat.mymedroads.com/hospitals" }
```

```json
// Response
{ "status": "deleted", "source": "document.txt", "chunksDeleted": 14 }
// Returns "not_found" with chunksDeleted: 0 if no matching chunks exist
```

### Health Check

**GET** `/conversations/health`

```json
{ "status": "UP", "service": "mymedroads-bot" }
```

---

## Managing the Knowledge Base

### Adding content

There are three ways to add content to the vector store:

**1. Classpath documents** — Drop `.txt` files into [`src/main/resources/knowledge/`](src/main/resources/knowledge/), rebuild, then call the ingest endpoint:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/documents
```

**2. URL crawl** — Crawl and ingest a live web page:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/url \
  -H "Content-Type: application/json" \
  -d '{"url": "https://uat.mymedroads.com"}'
```

**3. File upload** — Upload a document at runtime without rebuilding:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/upload \
  -F "file=@/path/to/document.txt" \
  -F "description=Hospital pricing guide for Q1 2026"
```

Documents are chunked into ~500-token segments, embedded via Ollama, and stored in PGVector. At query time the top 3 most relevant chunks are injected into the Claude system prompt.

### Removing content

Remove all chunks for a specific source by its filename or URL:

```bash
# Remove an uploaded or classpath document
curl -X DELETE http://localhost:8080/conversations/admin/ingest/remove \
  -H "Content-Type: application/json" \
  -d '{"source": "document.txt"}'

# Remove a crawled URL
curl -X DELETE http://localhost:8080/conversations/admin/ingest/remove \
  -H "Content-Type: application/json" \
  -d '{"source": "https://uat.mymedroads.com"}'
```

> Re-running ingestion on the same source creates duplicate chunks. Delete the source first, then re-ingest to refresh content.

---

## Project Structure

```
src/main/java/com/mymedroads/bot/
├── config/
│   └── AnthropicConfig.java          # Anthropic client bean
├── controller/
│   └── BotController.java            # REST endpoints
├── model/
│   ├── ChatMessage.java              # Conversation message
│   ├── ChatRequest.java              # Incoming request DTO
│   ├── ChatResponse.java             # Outgoing response DTO
│   └── PatientProfile.java           # Patient lead data model
└── service/
    ├── ClaudeService.java            # Claude API + RAG integration
    ├── ConversationSessionStore.java # In-memory session management
    ├── KnowledgeIngestionService.java # Document, URL, and file upload ingestion + deletion
    ├── PatientLeadApiService.java    # Patient lead CRM integration
    └── RagService.java               # Vector similarity search
```
