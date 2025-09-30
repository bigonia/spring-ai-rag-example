package com.zwbd.dbcrawlerv4.ai.web.controller;

import com.zwbd.dbcrawlerv4.ai.service.DocumentManagementService;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentChunkDTO;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentInfoDTO;
import com.zwbd.dbcrawlerv4.ai.dto.UpdateChunkRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:58
 * @Desc:
 */
@Tag(name = "Document Management API", description = "用于管理和预览知识库文档的接口")
@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private final DocumentManagementService documentManagementService;

    public DocumentManagementController(DocumentManagementService documentManagementService) {
        this.documentManagementService = documentManagementService;
    }

    @Operation(summary = "获取所有文档列表", description = "返回知识库中所有已入库文档的摘要信息列表。")
    @GetMapping
    public ResponseEntity<List<DocumentInfoDTO>> getAllDocuments() {
        return ResponseEntity.ok(documentManagementService.listAllDocuments());
    }

    @Operation(summary = "预览文档分片", description = "根据指定的 documentId，返回该文档被切分成的所有块（Chunks）的详细内容。")
    @GetMapping("/{documentId}")
    public ResponseEntity<List<DocumentChunkDTO>> getDocumentChunks(
            @Parameter(description = "要预览的文档的唯一ID", required = true)
            @PathVariable String documentId) {
        List<DocumentChunkDTO> chunks = documentManagementService.getDocumentChunks(documentId);
        if (chunks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(chunks);
    }

    @Operation(summary = "更新文档分片内容", description = "根据指定的分片ID (chunkId)，更新其文本内容。系统将自动重新计算向量。")
    @PutMapping("/chunks/{chunkId}")
    public ResponseEntity<Void> updateChunk(
            @Parameter(description = "要更新的分片的唯一ID (UUID)", required = true)
            @PathVariable String chunkId,
            @Valid @RequestBody UpdateChunkRequest request) {
        documentManagementService.updateChunkContent(chunkId, request.content());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "删除文档", description = "根据指定的 documentId，删除该文档及其对应的所有分片。")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "要删除的文档的唯一ID", required = true)
            @PathVariable String documentId) {
        documentManagementService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }
}
