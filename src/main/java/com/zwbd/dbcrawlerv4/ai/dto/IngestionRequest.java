package com.zwbd.dbcrawlerv4.ai.dto;

import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;

import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/9/16 10:55
 * @Desc: Ingestion request DTO for knowledge source processing
 * 
 * This DTO represents the request payload for the data ingestion endpoint.
 * It contains the source type, properties, and metadata for document processing.
 */
public record IngestionRequest(
        
        @NotNull(message = "Source type cannot be blank")
        RAGSourceType sourceType,
        
        @NotNull(message = "Properties cannot be null")
        Map<String, Object> properties,
        
        Map<String, Object> metadata
) {
    
    /**
     * Constructor with default empty metadata
     * @param sourceType The type of knowledge source (e.g., "PDF", "URL", "TEXT")
     * @param properties Source-specific properties (e.g., file path, URL)
     */
    public IngestionRequest(RAGSourceType sourceType, Map<String, Object> properties) {
        this(sourceType, properties, Map.of());
    }
}