package com.zwbd.dbcrawlerv4.ai.service;

import com.zwbd.dbcrawlerv4.ai.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.ai.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/9/17 17:08
 * @Desc:
 */
@Service
public class DocumentIngestService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestService.class);

    private final Map<DocumentType, DocumentLoader> documentLoaders;
    private final TokenTextSplitter textSplitter = new TokenTextSplitter();
    private final VectorRepository vectorRepository;

    /**
     * 构造函数，通过依赖注入获取所有 DocumentLoader 的实现。
     *
     * @param loaderImplementations Spring 容器中所有 DocumentLoader bean 的列表。
     * @param vectorRepository      数据访问仓库。
     */
    public DocumentIngestService(List<DocumentLoader> loaderImplementations, VectorRepository vectorRepository) {
        documentLoaders = new HashMap<>();
        if (loaderImplementations != null) {
            for (DocumentLoader loader : loaderImplementations) {
                Set<DocumentType> supportedTypes = loader.getSourceType();
                if (supportedTypes == null || supportedTypes.isEmpty()) {
                    logger.warn("DocumentLoader {} did not register any DocumentTypes, skipping.", loader.getClass().getSimpleName());
                    continue;
                }
                for (DocumentType type : supportedTypes) {
                    DocumentLoader existingLoader = documentLoaders.put(type, loader);
                    if (existingLoader != null) {
                        logger.warn("Duplicate DocumentLoader registration for type: {}. Using implementation: {} and overwriting: {}",
                                type,
                                loader.getClass().getSimpleName(),
                                existingLoader.getClass().getSimpleName());
                    }
                }
            }
        }
        this.vectorRepository = vectorRepository;
        logger.info("Initialized IngestionService with the following document loaders: {}", this.documentLoaders.keySet());
    }

    /**
     * 执行数据注入流程的主方法。
     *
     * @param metadata 包含知识源信息的数据注入请求。
     */
    public List<Document> ingest(BaseMetadata metadata, boolean preview) {
        DocumentType sourceType = metadata.getDocumentType();
        logger.info("Starting ingestion process for sourceType: {}", sourceType);

        // 1. & 2. 路由和选择 DocumentLoader
        DocumentLoader loader = documentLoaders.get(sourceType);
        if (loader == null) {
            logger.error("No DocumentLoader found for sourceType: '{}'. Aborting ingestion.", sourceType);
            throw new IllegalArgumentException("Unsupported source type: " + sourceType);
        }

        // 3. 加载文档
        logger.debug("Using loader: {}", loader.getClass().getSimpleName());
        List<Document> documents = loader.load(metadata);
        if (documents.isEmpty()) {
            logger.warn("Loader for sourceType '{}' returned no documents. Ingestion process finished.", sourceType);
            return documents;
        }

        // 4. 切分文档
        List<Document> chunks = textSplitter.split(documents);
        if (chunks.isEmpty()) {
            logger.warn("Text splitter returned no chunks. Ingestion process finished.");
            return chunks;
        }

        // 5. 持久化
        if (!preview) {
            logger.info("Saving {} document chunks to the vector store.", chunks.size());
            vectorRepository.save(chunks);
            logger.info("Successfully completed ingestion for sourceType: {}", sourceType);
        }
        return chunks;
    }


}
