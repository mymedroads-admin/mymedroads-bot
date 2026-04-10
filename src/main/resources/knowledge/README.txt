This folder contains knowledge base documents for the MyMedRoads RAG pipeline.

Supported file types:
  - .txt  Plain text files
  - .pdf  PDF documents

How to add content:
  1. Drop your .pdf or .txt files into this folder
  2. Rebuild the WAR (mvn package)
  3. Call POST /conversations/admin/ingest/documents to load them into the vector store

For website content, call:
  POST /conversations/admin/ingest/url
  Body: { "url": "https://uat.mymedroads.com/hospitals" }

Notes:
  - Documents are chunked into ~500-token segments before embedding
  - The vector store (PGVector) must be running before ingestion
  - Ingestion is idempotent but will add duplicate chunks if run multiple times
    on the same content — clear the vector_store table first if re-ingesting
