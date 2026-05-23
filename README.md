# MyMedRoads Bot — Mira

A conversational AI assistant for [MyMedRoads](https://uat.mymedroads.com) — a platform that helps people find hospitals, plan medical travel, and arrange support services.

Mira is built with **Spring Boot 3.3**, **Claude (Anthropic)** for chat, and **RAG (Retrieval-Augmented Generation)** using **Ollama embeddings** + **PGVector** for domain-specific knowledge retrieval.

---

## Architecture

```
Client request  (message + sessionId? + clientId?)
        │
        ▼
Session resolution  ──── clientId lookup ──▶ language-preferences.json
        │                                     (restore saved language, skip prompt)
        ▼
Embed query (Ollama: nomic-embed-text)
        │
        ▼
Similarity search → PGVector (top 3 chunks)
        │
        ▼
Build system prompt  (base + RAG context + language enforcement)
        │
        ▼
Claude API  (claude-sonnet-4-6) → Response
        │
        ▼
Marker processing  [INTAKE_COMPLETE] / [LANGUAGE_SELECTED] / [INTAKE_UPDATE] / [CASE_STATUS_REQUEST]
        │
        ▼
Persist session to sessions.json  (every 60 s)
Persist language preference to language-preferences.json  (on selection + every 60 s)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.3 (WAR, external Tomcat 10) |
| Chat / Generation | Anthropic Claude (`claude-sonnet-4-6`) |
| Embeddings | Ollama (`nomic-embed-text`) — local |
| Vector Store | PGVector (PostgreSQL extension) |
| RAG Framework | Spring AI 1.0 |
| Logging | Log4j2 |
| Build | Maven |
| Java | 21 |

---

## Features

- **Multi-language support** — English (default), Hindi, Bengali, Swahili, Arabic, French, Spanish, Chinese Mandarin, German, Russian, Amharic
- **Persistent language preference** — remembered across sessions via `clientId`; returning users go straight to intake in their language
- **Guided patient intake** — collects name, age, gender, mobile, email, destination, and medical issue one at a time; submits as a lead to the myMedRoads CRM
- **Case status lookup** — fetches live case status from the CRM using the patient's URN
- **RAG-powered answers** — hospital info, medical travel, visa, accommodation, and logistics sourced from an ingested knowledge base
- **Session persistence** — conversation history survives server restarts via `sessions.json`

---

## Prerequisites

- Java 21
- Maven 3.8+
- Docker (for PGVector)
- [Ollama](https://ollama.com) with `nomic-embed-text` model pulled
- Anthropic API key
- myMedRoads API suite URL

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
export ANTHROPIC_MODEL=claude-sonnet-4-6
export API_URL=https://api.mymedroads.com     # myMedRoads backend
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=mymedroads
export DB_USER=bot
export DB_PASSWORD=secret
export OLLAMA_BASE_URL=http://localhost:11434
# Optional — override default file paths
export SESSIONS_LOG_FILE=./sessions.json
export LANG_PREFS_FILE=./language-preferences.json
```

### 4. Run the application

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

> For Tomcat deployment, build with `mvn package` and deploy `target/mymedroads-bot.war`.
> The context path becomes `/mymedroads-bot` automatically from the WAR filename.

---

## Configuration

All configuration is in [`src/main/resources/application.yml`](src/main/resources/application.yml).

| Environment variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Anthropic API key (required) |
| `ANTHROPIC_MODEL` | — | Claude model ID, e.g. `claude-sonnet-4-6` |
| `ANTHROPIC_MAX_TOKENS` | `1024` | Max response tokens |
| `API_URL` | — | myMedRoads API suite base URL |
| `DB_HOST` | — | PostgreSQL host |
| `DB_PORT` | — | PostgreSQL port |
| `DB_NAME` | — | Database name |
| `DB_USER` | — | Database username |
| `DB_PASSWORD` | — | Database password |
| `OLLAMA_BASE_URL` | — | Ollama server URL |
| `SESSIONS_LOG_FILE` | `./sessions.json` | Path for persisted session history |
| `LANG_PREFS_FILE` | `./language-preferences.json` | Path for persisted language preferences |

---

## API Endpoints

### Chat

**POST** `/conversations/chat`

Send a message. Omit `sessionId` to start a new conversation. Always return `clientId` to the same user so their language preference is remembered across sessions.

```json
// Request
{
  "message": "Hello Mira",
  "sessionId": "optional — omit for a new session",
  "clientId": "optional — persistent device/user identifier"
}

// Response — first turn, language not yet known
// The intro and the language selection list are combined in "message"
{
  "sessionId": "abc-123",
  "clientId": "xyz-456",
  "message": "Hello! I'm Mira...\n\nI support the following languages. Please reply with the number or name...\n1. English\n2. Hindi\n...",
  "model": "claude-sonnet-4-6",
  "inputTokens": 512,
  "outputTokens": 180
}

// Response — first turn, returning user (language already saved for this clientId)
// Language selection is skipped; bot responds immediately in the saved language
{
  "sessionId": "abc-123",
  "clientId": "xyz-456",
  "message": "Hello! I'm Mira, your medical travel assistant from myMedRoads...",
  "model": "claude-sonnet-4-6",
  "inputTokens": 512,
  "outputTokens": 180
}
```

**Language preference flow:**

| Scenario | Behaviour |
|---|---|
| New client, no `clientId` | Server generates a `clientId`; bot presents language menu after intro |
| Returning client, language known | Bot skips language menu; intro and all replies are in the saved language |
| User selects a language | Selection is saved to `language-preferences.json` keyed by `clientId` |
| Server restart | Language restored from `language-preferences.json` when `clientId` is provided |

> The client must store the `clientId` from the first response (e.g. in localStorage or a cookie) and include it in all subsequent requests, including new sessions.

### Session Management

**POST** `/conversations/session/new` — Create a new session explicitly

**GET** `/conversations/session/{sessionId}/transcript` — Retrieve full message history for a session

**DELETE** `/conversations/session/{sessionId}` — Clear a session and its history

### Knowledge Ingestion (RAG)

**POST** `/conversations/admin/ingest/documents` — Ingest all `.txt` files from `src/main/resources/knowledge/`

**POST** `/conversations/admin/ingest/url` — Crawl and ingest a URL

```json
{ "url": "https://uat.mymedroads.com/hospitals" }
```

**POST** `/conversations/admin/ingest/upload` — Upload a document at runtime (`multipart/form-data`)

| Field | Type | Description |
|---|---|---|
| `file` | file | Plain-text document |
| `description` | text | Brief summary of the document's content |

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
{ "source": "document.txt" }
{ "source": "https://uat.mymedroads.com/hospitals" }
```

```json
// Response
{ "status": "deleted", "source": "document.txt", "chunksDeleted": 14 }
```

### Health Check

**GET** `/conversations/health`

```json
{ "status": "UP", "service": "mymedroads-bot" }
```

---

## Conversation Flow

```
1. Greeting / first message
        │
        ▼
2. Mira introduces herself
        │
        ├── Language preference known (returning client) ──▶ Skip to step 4
        │
        └── Language preference unknown
                │
                ▼
        3. User selects language (saved to language-preferences.json)
                │
                ▼
        4. Patient intake  (name → age → gender → mobile → email → destination → medical issue)
                │
                ▼
        5. Mira summarises and requests confirmation + data-privacy consent
                │
                ▼
        6. Lead submitted to CRM → URN returned to user
                │
                ▼
        7. Post-intake preferences  (hospital, doctor, accommodation, budget)
                │
                ▼
        8. Open Q&A  (hospitals, travel, visa, accommodation, logistics)
```

---

## Managing the Knowledge Base

Documents are chunked into ~500-token segments, embedded via Ollama, and stored in PGVector. At query time the top 3 most relevant chunks are injected into the Claude system prompt.

### Adding content

**Classpath documents** — Drop `.txt` files into [`src/main/resources/knowledge/`](src/main/resources/knowledge/), rebuild, then call:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/documents
```

**URL crawl** — Crawl and ingest a live web page:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/url \
  -H "Content-Type: application/json" \
  -d '{"url": "https://uat.mymedroads.com"}'
```

**File upload** — Upload a document at runtime without rebuilding:

```bash
curl -X POST http://localhost:8080/conversations/admin/ingest/upload \
  -F "file=@/path/to/document.txt" \
  -F "description=Hospital pricing guide for Q1 2026"
```

### Removing content

```bash
# By filename
curl -X DELETE http://localhost:8080/conversations/admin/ingest/remove \
  -H "Content-Type: application/json" \
  -d '{"source": "document.txt"}'

# By URL
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
│   ├── AnthropicConfig.java             # Anthropic client bean
│   └── OllamaHttpConfig.java            # Ollama REST client (timeouts)
├── controller/
│   └── BotController.java               # REST endpoints
├── model/
│   ├── ChatMessage.java                 # role + content pair
│   ├── ChatRequest.java                 # message + sessionId + clientId
│   ├── ChatResponse.java                # message + sessionId + clientId + tokens
│   └── PatientProfile.java             # Patient lead data
└── service/
    ├── ClaudeService.java               # Orchestration: Claude API, RAG, markers, clientId
    ├── ConversationSessionStore.java    # Session + language preference persistence
    ├── KnowledgeIngestionService.java   # Document / URL / file ingestion and deletion
    ├── PatientLeadApiService.java       # CRM integration (submit, update, case status)
    └── RagService.java                  # PGVector similarity search
```

### Persistence files

| File | Contents | Persisted |
|---|---|---|
| `sessions.json` | Full conversation history per session | Every 60 s + on selection |
| `language-preferences.json` | `clientId → language` map | On each language selection + every 60 s |
