package com.mymedroads.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;

    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /**
     * Retrieve the most relevant knowledge chunks for the given query.
     * Returns an empty string if no relevant content is found.
     */
    public String retrieveContext(String userQuery) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userQuery != null ? userQuery : "")
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );

        if (results == null || results.isEmpty()) {
            log.debug("No relevant knowledge found for query: {}", userQuery);
            return "";
        }

        log.debug("Found {} relevant chunks for query: {}", results.size(), userQuery);

        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining(
                        "\n\n---\n\n",
                        "RELEVANT KNOWLEDGE FROM MYMEDROADS DATABASE:\n\n",
                        "\n\nEND OF KNOWLEDGE"
                ));
    }
}
