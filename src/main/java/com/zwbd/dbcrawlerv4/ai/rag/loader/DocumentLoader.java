package com.zwbd.dbcrawlerv4.ai.rag.loader;

import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/16 11:15
 * @Desc: Document loader interface for different knowledge sources
 * <p>
 * This interface defines the contract for loading documents from various sources
 * such as files, URLs, databases, etc. Each implementation handles a specific source type.
 */
public interface DocumentLoader {

    /**
     * Get the source type this loader supports
     *
     * @return Source type identifier (e.g., "PDF", "URL", "TEXT")
     */
    RAGSourceType getSourceType();

    /**
     * Load documents from the specified source
     *
     * @param request
     * @return
     * @throws Exception
     */
    List<Document> load(IngestionRequest request);

    /**
     * 流式处理
     *
     * @param request
     * @return
     */
    Stream<Document> stream(IngestionRequest request);

}