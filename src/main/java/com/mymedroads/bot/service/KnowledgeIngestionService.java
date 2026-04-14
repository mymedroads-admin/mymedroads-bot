package com.mymedroads.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PathMatchingResourcePatternResolver resourceResolver =
            new PathMatchingResourcePatternResolver();

    // ~500 tokens at avg 4 chars/token = 2000 chars; 200-char overlap
    private static final int CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 200;

    /**
     * Ingest all .txt documents from classpath:knowledge/.
     * PDFs should be pre-converted to .txt before placing in this folder.
     */
    public int ingestDocuments() throws IOException {
        List<Document> allDocs = new ArrayList<>();

        Resource[] txts = resourceResolver.getResources("classpath:knowledge/*.txt");
        for (Resource txt : txts) {
            if ("README.txt".equals(txt.getFilename())) continue;
            log.info("Loading TXT: {}", txt.getFilename());
            String text = new String(txt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            allDocs.add(new Document(text, Map.of("source", txt.getFilename())));
        }

        if (allDocs.isEmpty()) {
            log.warn("No documents found in classpath:knowledge/ (excluding README.txt)");
            return 0;
        }

        List<Document> chunks = splitDocuments(allDocs);
        log.info("Ingesting {} chunks from {} documents...", chunks.size(), allDocs.size());
        vectorStore.add(chunks);
        log.info("Document ingestion complete.");
        return chunks.size();
    }

    /**
     * Ingest a document uploaded via API along with a brief description.
     * The description is stored as metadata and prepended to the content so it
     * influences both retrieval relevance and generated answers.
     *
     * @param file        the uploaded file (plain-text content expected)
     * @param description a brief human-readable summary of what the document contains
     * @return number of chunks ingested into the vector store
     */
    public int ingestUploadedDocument(MultipartFile file, String description) throws IOException {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded-document";
        log.info("Ingesting uploaded document: {} — description: {}", filename, description);

        String rawText = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (rawText.isBlank()) {
            log.warn("Uploaded document '{}' is empty — skipping.", filename);
            return 0;
        }

        // Prepend the description so it influences embedding similarity for retrieval
        String enrichedText = "[Description: " + description + "]\n\n" + rawText;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", filename);
        metadata.put("description", description);

        List<Document> chunks = splitDocuments(List.of(new Document(enrichedText, metadata)));
        log.info("Ingesting {} chunks from uploaded document '{}'", chunks.size(), filename);
        vectorStore.add(chunks);
        log.info("Upload ingestion complete: {}", filename);
        return chunks.size();
    }

    /**
     * Crawl a URL and ingest its text content using Spring AI's Jsoup document reader.
     */
    public int ingestUrl(String url) throws IOException {
        log.info("Crawling URL: {}", url);
        org.jsoup.nodes.Document jsoupDoc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .timeout(15_000)
                .get();

        Element body = jsoupDoc.body();
        String text = body != null ? body.text() : "";

        List<Document> docs = new ArrayList<>();
        if (!text.isBlank()) {
            docs.add(new Document(text, Map.of("source", url)));
        }

        if (docs.isEmpty()) {
            log.warn("No content found at URL: {}", url);
            return 0;
        }

        List<Document> chunks = splitDocuments(docs);
        log.info("Ingesting {} chunks from URL: {}", chunks.size(), url);
        vectorStore.add(chunks);
        log.info("URL ingestion complete: {}", url);
        return chunks.size();
    }

    /**
     * Delete all vector store chunks whose metadata source matches the given value.
     * Works for both filenames (from file/classpath ingestion) and URLs.
     *
     * @param source the exact source value stored at ingest time (filename or URL)
     * @return number of chunks deleted
     */
    public int deleteBySource(String source) {
        log.info("Deleting vector store chunks for source: {}", source);
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'source' = ?", source);
        log.info("Deleted {} chunks for source: {}", deleted, source);
        return deleted;
    }

    /**
     * Character-based text splitter. Replaces TokenTextSplitter (avoids jtokkit dependency).
     * Chunks documents into CHUNK_SIZE characters with CHUNK_OVERLAP overlap.
     */
    private List<Document> splitDocuments(List<Document> docs) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                // Try to break at a sentence boundary near the end
                if (end < text.length()) {
                    int boundary = lastSentenceBoundary(text, end - 100, end);
                    if (boundary > start) end = boundary;
                }
                chunks.add(new Document(text.substring(start, end), doc.getMetadata()));
                if (end >= text.length()) break;
                start = end - CHUNK_OVERLAP;
            }
        }
        return chunks;
    }

    /** Returns the index just after the last '.', '!', or '?' in text[from..to]. */
    private int lastSentenceBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') return i + 1;
        }
        return -1;
    }
}
