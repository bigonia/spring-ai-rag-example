package com.zwbd.dbcrawlerv4.ai.etl.loader.impl;

import com.zwbd.dbcrawlerv4.ai.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.FileUploadMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.config.CommonConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/18 9:07
 * @Desc:
 */
@Slf4j
@Component
public class MarkdownDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
//        Resource resource = new FileSystemResource(filePathStr);
        // 配置并使用 Spring AI 的原生 Reader
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                // 附加所有元数据
                .withAdditionalMetadata(fileUploadMetadata.toMap(CommonConfig.objectMapper))
                //设为 true 时，Markdown 中的水平分隔符将生成新 Document 对象。
                .withHorizontalRuleCreateDocument(false)
                //设为 true 时，代码块将与周边文本合并到同一 Document；设为 false 时，代码块生成独立 Document 对象。
                .withIncludeCodeBlock(false)
                //设为 true 时，引用块将与周边文本合并到同一 Document；设为 false 时，引用块生成独立 Document 对象。
                .withIncludeBlockquote(true)
                .build();

        MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
        return reader.get();

//        Path filePath = Paths.get(filePathStr);
//        log.info("Loading Markdown document from path: {}", filePath);
//
//        try {
//            String content = Files.readString(filePath);
//            Document document = new Document(content, fileUploadMetadata.toMap(CommonConfig.objectMapper));
//
//            log.info("Successfully loaded document from Markdown: {}", filePath.getFileName());
//            return List.of(document);
//
//        } catch (IOException e) {
//            log.error("Failed to read Markdown file at path: {}", filePath, e);
//            throw new RuntimeException("Failed to read Markdown file", e);
//        }
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.MD);
    }
}
