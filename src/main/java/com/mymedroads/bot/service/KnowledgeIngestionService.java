package com.mymedroads.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final VectorStore vectorStore;
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
     * Crawl a URL and ingest its text content using Spring AI's Jsoup document reader.
     */
    public int ingestUrl(String url) throws IOException {
        log.info("Crawling URL: {}", url);
        Resource urlResource = new UrlResource(url);
        JsoupDocumentReader reader = new JsoupDocumentReader(urlResource);
        List<Document> docs = reader.get();

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
