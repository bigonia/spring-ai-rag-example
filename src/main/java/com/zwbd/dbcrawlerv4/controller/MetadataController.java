package com.zwbd.dbcrawlerv4.controller;

import com.zwbd.dbcrawlerv4.ai.service.RAGService;
import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import com.zwbd.dbcrawlerv4.dto.database.DataBaseInfoDTO;
import com.zwbd.dbcrawlerv4.dto.metadata.DatabaseMetadata;
import com.zwbd.dbcrawlerv4.service.DataBaseInfoService;
import com.zwbd.dbcrawlerv4.service.MetadataCollectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * @Author: wnli
 * @Date: 2025/9/19 11:38
 * @Desc:
 */
@Slf4j
@RestController
@RequestMapping("/api/metadata")
@AllArgsConstructor
@Tag(name = "Database metadata", description = "Database metadata management APIs")
public class MetadataController {

    private final MetadataCollectorService metadataCollectorService;

    private final DataBaseInfoService dataBaseInfoService;

    private final RAGService RAGService;

    @PostMapping("/check/{id}")
    @Operation(summary = "check database metadata", description = "check database metadata")
    public ResponseEntity<DatabaseMetadata> check(@Parameter(description = "Database info ID") @PathVariable Long id) {
        Optional<DataBaseInfoDTO> dto = dataBaseInfoService.findById(id);
        CompletableFuture<DatabaseMetadata> future = metadataCollectorService.collectMetadata(dto.get().toEntity());
        DatabaseMetadata databaseMetadata = null;
        try {
            databaseMetadata = future.get();
        } catch (Exception e) {
            log.error("Error creating database metadata", e);
        }
        log.info("============================================================");
        log.info("Check database metadata: {}", databaseMetadata.databaseProductName());
        return ResponseEntity.ok(databaseMetadata);
    }


    @PostMapping("/analyse/{id}")
    @Operation(summary = "analyse database metadata to RAG sys", description = "analyse database")
    public void analyse(@Parameter(description = "Database info ID") @PathVariable Long id) {
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> prop = new HashMap<>();
        prop.put("databaseId", id);
        metadata.put("document_id", id);
//        metadata.put("databaseId", id);
        IngestionRequest ingestionRequest = new IngestionRequest(RAGSourceType.DATABASE, prop, metadata);
        RAGService.ingest(ingestionRequest);
    }

}
