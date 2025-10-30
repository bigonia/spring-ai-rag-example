package com.zwbd.dbcrawlerv4.ai.etl.loader.impl;

import com.zwbd.dbcrawlerv4.ai.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DatabaseRecordMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.config.CommonConfig;
import com.zwbd.dbcrawlerv4.dto.database.DataBaseInfoDTO;
import com.zwbd.dbcrawlerv4.dto.metadata.DatabaseMetadata;
import com.zwbd.dbcrawlerv4.service.DataBaseInfoService;
import com.zwbd.dbcrawlerv4.service.DatabaseMetadataProcessor;
import com.zwbd.dbcrawlerv4.service.MetadataCollectorService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Simple DocumentLoader implementation for database metadata
 * <p>
 * Loads database metadata and converts it to Markdown documents for RAG ingestion.
 * Focuses on core functionality without complex configuration options.
 */
@Slf4j
@Component
@AllArgsConstructor
public class DatabaseMetadataDocumentLoader implements DocumentLoader {

    private final MetadataCollectorService metadataCollectorService;

    private final DataBaseInfoService dataBaseInfoService;

    private final DatabaseMetadataProcessor databaseMetadataProcessor;


    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DATABASE);
    }

    @Override
    public List<Document> load(BaseMetadata metadata) {
        DatabaseRecordMetadata databaseRecordMetadata = (DatabaseRecordMetadata) metadata;
        long databaseID = databaseRecordMetadata.getDatabaseId();
        Optional<DataBaseInfoDTO> dto = dataBaseInfoService.findById(databaseID);
        CompletableFuture<DatabaseMetadata> future = metadataCollectorService.collectMetadata(dto.get().toEntity());
        DatabaseMetadata databaseMetadata = null;
        try {
            databaseMetadata = future.get();
            log.info("DatabaseMetadata loaded");
            Stream<Document> stream = databaseMetadataProcessor.process(databaseMetadata, metadata.toMap(CommonConfig.objectMapper));
            List<Document> list = stream.toList();
            return list;
        } catch (Exception e) {
            log.error("Error creating database metadata", e);
        }
        return List.of();
    }

}