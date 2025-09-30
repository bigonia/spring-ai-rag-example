package com.zwbd.dbcrawlerv4.ai.web.controller;

import com.zwbd.dbcrawlerv4.ai.file.FileStorageService;
import com.zwbd.dbcrawlerv4.ai.service.RAGService;
import com.zwbd.dbcrawlerv4.ai.dto.FileUploadResponse;
import com.zwbd.dbcrawlerv4.ai.dto.IngestionRequest;
import com.zwbd.dbcrawlerv4.ai.enums.RAGSourceType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/9/18 9:09
 * @Desc:
 */
@Tag(name = "File Manager API", description = "文件管理")
@RestController
@RequestMapping("/api/v1")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private final FileStorageService fileStorageService;
    private final RAGService RAGService;

    public FileController(FileStorageService fileStorageService, RAGService RAGService) {
        this.fileStorageService = fileStorageService;
        this.RAGService = RAGService;
    }

    /**
     * 上传单个文件并进行处理。
     *
     * @param file         上传的文件
     * @param sourceSystem （可选）文件来源的系统
     * @param documentType （可选）文件类型
     * @return 响应结果
     */
    @Operation(summary = "上传知识文件",
            description = "上传一个文件（如.md, .pdf），系统会自动解析、处理并存入向量数据库中。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "文件上传成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(implementation = FileUploadResponse.class))),
                    @ApiResponse(responseCode = "400", description = "无效的请求或文件为空"),
                    @ApiResponse(responseCode = "500", description = "服务器内部错误")
            })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadFile(
            @Parameter(description = "要上传的知识文件。", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "知识来源的系统标识，例如 'HR_System' 或 'Confluence'。", example = "HR_System")
            @RequestParam(value = "source_system", required = false) String sourceSystem,
            @Parameter(description = "文档的业务类型，例如 'Policy', 'Manual', 'FAQ'。", example = "Policy")
            @RequestParam(value = "document_type", required = false) String documentType) {

        // 1. 存储文件到本地
        Path storedFilePath = fileStorageService.store(file);
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());

        // 2. 准备注入请求
        Map<String, Object> properties = new HashMap<>();
        properties.put("filePath", storedFilePath.toString());

        // 3. 准备元数据
        // 为该文档生成一个唯一的ID，用于后续的删除和管理
        String documentId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("document_id", documentId); // 关键元数据
        metadata.put("original_filename", originalFilename);
        if (StringUtils.hasText(sourceSystem)) {
            metadata.put("source_system", sourceSystem);
        }
        if (StringUtils.hasText(documentType)) {
            metadata.put("document_type", documentType);
        }

        // 4. 根据文件类型确定 sourceType
        String sourceType = getSourceTypeFromFile(originalFilename);

        IngestionRequest ingestionRequest = new IngestionRequest(RAGSourceType.valueOf(sourceType), properties, metadata);

        // 5. 调用注入服务
        RAGService.ingest(ingestionRequest);

        // 6. 返回成功响应
        FileUploadResponse response = new FileUploadResponse(
                documentId,
                originalFilename,
                "File uploaded and scheduled for ingestion successfully."
        );
        log.info("ingest file uploaded successfully");
        return ResponseEntity.ok(response);
    }

    /**
     * 根据文件名后缀判断数据源类型。
     */
    private String getSourceTypeFromFile(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            // 默认或抛出异常
            return "UNKNOWN";
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return switch (extension.toLowerCase()) {
            case "md" -> "MD";
            case "pdf" -> "PDF";
            // ... 可以扩展更多文件类型
            default -> extension;
        };
    }
}
