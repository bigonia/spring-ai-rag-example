package com.zwbd.dbcrawlerv4.ai.etl.loader.impl;

import com.zwbd.dbcrawlerv4.ai.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.config.CommonConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public List<Document> load(BaseMetadata request)  {
        log.debug("Loading text document");

        Map<String, Object> metadata = request.toMap(CommonConfig.objectMapper);


        Document document = new Document("", metadata);
        
        log.debug("Successfully loaded text document with ID: {}", request.getDocumentId());
        
        return List.of(document);
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.TEXT);
    }

}