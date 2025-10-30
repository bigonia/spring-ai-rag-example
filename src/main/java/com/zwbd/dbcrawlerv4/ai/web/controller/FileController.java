package com.zwbd.dbcrawlerv4.ai.web.controller;

import com.zwbd.dbcrawlerv4.ai.file.FileStorageService;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.ai.metadata.FileUploadMetadata;
import com.zwbd.dbcrawlerv4.ai.service.DocumentIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
    private final DocumentIngestService DocumentIngestService;

    public FileController(FileStorageService fileStorageService, DocumentIngestService DocumentIngestService) {
        this.fileStorageService = fileStorageService;
        this.DocumentIngestService = DocumentIngestService;
    }

    /**
     * 上传单个文件并进行处理。
     *
     * @param file         上传的文件
     * @param sourceSystem 文件来源的系统
     * @return 响应结果
     */
    @Operation(summary = "上传知识文件",
            description = "上传一个文件（如.md, .pdf），系统会自动解析、处理并存入向量数据库中。"
    )
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Document> uploadFile(
            @Parameter(description = "要上传的知识文件。", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档来源")
            @RequestParam(value = "sourceSystem", required = false) String sourceSystem,
            @Parameter(description = "是否预览")
            @RequestParam(value = "true", required = false, defaultValue = "false") boolean preview) {
        if (StringUtils.isEmpty(sourceSystem)) {
            sourceSystem = "default";
        }
        FileUploadMetadata metaData = new FileUploadMetadata();
        metaData.setSourceSystem(sourceSystem);
        // 1. 存储文件到本地
        Path storedFilePath = fileStorageService.store(file);
        metaData.setFilePath(storedFilePath.toString());
        String originalFilename = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "unknown"));
        metaData.setOriginalFilename(originalFilename);

        // 2. 根据文件类型确定 sourceType
        DocumentType sourceType = getSourceTypeFromFile(originalFilename);
        metaData.setDocumentType(sourceType);

        // 3. 调用注入服务
        List<Document> documents = DocumentIngestService.ingest(metaData, preview);
        log.info("ingest file uploaded successfully");
        return documents;
    }

    /**
     * 根据文件名后缀判断数据源类型。
     */
    private DocumentType getSourceTypeFromFile(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return DocumentType.UNKNOWN;
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return DocumentType.valueOf(extension.toUpperCase());
    }
}
