package com.zwbd.dbcrawlerv4.ai.etl.loader;

import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Set;
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
     * Load documents from the specified source
     *
     * @param metadata
     * @return
     * @throws Exception
     */
    List<Document> load(BaseMetadata metadata);

    /**
     * Get the source type this loader supports
     *
     * @return Source type identifier (e.g., "PDF", "URL", "TEXT")
     */
    Set<DocumentType> getSourceType();

}