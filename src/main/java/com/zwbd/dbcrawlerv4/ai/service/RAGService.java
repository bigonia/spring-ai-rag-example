package com.zwbd.dbcrawlerv4.ai.service;

import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import com.zwbd.dbcrawlerv4.ai.rag.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.repository.RAGRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/17 17:08
 * @Desc:
 */
@Service
public class RAGService {

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);

    private final Map<RAGSourceType, DocumentLoader> documentLoaders;
    private final TokenTextSplitter textSplitter = new TokenTextSplitter();
    private final RAGRepository ragRepository;

    /**
     * 构造函数，通过依赖注入获取所有 DocumentLoader 的实现。
     *
     * @param loaderImplementations Spring 容器中所有 DocumentLoader bean 的列表。
     * @param ragRepository         数据访问仓库。
     */
    public RAGService(List<DocumentLoader> loaderImplementations,

                      RAGRepository ragRepository) {
        this.documentLoaders = loaderImplementations.stream()
                .collect(Collectors.toMap(DocumentLoader::getSourceType, Function.identity()));
        this.ragRepository = ragRepository;
        logger.info("Initialized IngestionService with the following document loaders: {}", this.documentLoaders.keySet());
    }

    /**
     * 执行数据注入流程的主方法。
     *
     * @param request 包含知识源信息的数据注入请求。
     */
    public void ingest(IngestionRequest request) {
        RAGSourceType sourceType = request.sourceType();
        logger.info("Starting ingestion process for sourceType: {}", sourceType);

        // 1. & 2. 路由和选择 DocumentLoader
        DocumentLoader loader = documentLoaders.get(sourceType);
        if (loader == null) {
            logger.error("No DocumentLoader found for sourceType: '{}'. Aborting ingestion.", sourceType);
            throw new IllegalArgumentException("Unsupported source type: " + sourceType);
        }

        // 3. 加载文档
        logger.debug("Using loader: {}", loader.getClass().getSimpleName());
        List<Document> documents = loader.load(request);
        if (documents.isEmpty()) {
            logger.warn("Loader for sourceType '{}' returned no documents. Ingestion process finished.", sourceType);
            return;
        }

        // 4. 切分文档
        List<Document> chunks = textSplitter.split(documents);
        if (chunks.isEmpty()) {
            logger.warn("Text splitter returned no chunks. Ingestion process finished.");
            return;
        }

        // 5. 持久化
        logger.info("Saving {} document chunks to the vector store.", chunks.size());
        ragRepository.save(chunks);
        logger.info("Successfully completed ingestion for sourceType: {}", sourceType);
    }


}
