package com.zwbd.dbcrawlerv4.ai.rag.loader.impl;

import com.zwbd.dbcrawlerv4.ai.rag.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/18 9:07
 * @Desc:
 */
@Component
public class MarkdownDocumentLoader implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownDocumentLoader.class);
    private static final String PROPERTY_KEY = "filePath";

    @Override
    public List<Document> load(IngestionRequest request) {
        String filePathStr = (String) request.properties().get(PROPERTY_KEY);
        if (filePathStr == null || filePathStr.isBlank()) {
            logger.warn("Markdown file path is missing in properties for sourceType '{}'", RAGSourceType.MD);
            return Collections.emptyList();
        }

        Path filePath = Paths.get(filePathStr);
        logger.info("Loading Markdown document from path: {}", filePath);

        try {
            String content = Files.readString(filePath);
            Map<String, Object> metadata = request.metadata();

            // 将原始文件名和路径作为元数据的一部分添加，便于追溯
            metadata.put("source_file", filePath.getFileName().toString());
            metadata.put("source_path", filePath.toString());

            Document document = new Document(content, metadata);

            logger.info("Successfully loaded document from Markdown: {}", filePath.getFileName());
            return List.of(document);

        } catch (IOException e) {
            logger.error("Failed to read Markdown file at path: {}", filePath, e);
            throw new RuntimeException("Failed to read Markdown file", e);
        }
    }

    @Override
    public Stream<Document> stream(IngestionRequest request) {
        return Stream.empty();
    }

    @Override
    public RAGSourceType getSourceType() {
        return RAGSourceType.MD;
    }
}
