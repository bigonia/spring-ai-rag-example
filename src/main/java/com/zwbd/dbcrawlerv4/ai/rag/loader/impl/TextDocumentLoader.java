package com.zwbd.dbcrawlerv4.ai.rag.loader.impl;

import com.zwbd.dbcrawlerv4.ai.rag.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/16 11:18
 * @Desc: Text document loader for plain text content
 * 
 * This loader handles plain text content provided directly in the request.
 * It's useful for ingesting text snippets, articles, or any textual content.
 */
@Slf4j
@Component
public class TextDocumentLoader implements DocumentLoader {
    
    private static final String CONTENT_PROPERTY = "content";
    private static final String TITLE_PROPERTY = "title";
    
    @Override
    public RAGSourceType getSourceType() {
        return RAGSourceType.TEXT;
    }
    
    @Override
    public List<Document> load(IngestionRequest request)  {
        log.debug("Loading text document with properties: {}", request.properties().keySet());

        Map<String, Object> metadata = request.metadata();

        String content = (String) request.properties().get(CONTENT_PROPERTY);
        String title = (String) request.properties().getOrDefault(TITLE_PROPERTY, "Untitled Document");
        
        // Generate document ID
        String documentId = UUID.randomUUID().toString();
        
        // Prepare document metadata
        Map<String, Object> docMetadata = Map.of(
                "document_id", documentId,
                "source_type", RAGSourceType.TEXT,
                "title", title,
                "chunk_sequence", 0
        );
        
        // Add any additional metadata
        if (metadata != null && !metadata.isEmpty()) {
            docMetadata = new java.util.HashMap<>(docMetadata);
            docMetadata.putAll(metadata);
        }
        
        Document document = new Document(content, docMetadata);
        
        log.debug("Successfully loaded text document with ID: {}", documentId);
        
        return List.of(document);
    }

    @Override
    public Stream<Document> stream(IngestionRequest request) {
        return Stream.empty();
    }

}