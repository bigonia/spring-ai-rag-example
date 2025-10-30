package com.zwbd.dbcrawlerv4.ai.etl.loader.impl;

import com.zwbd.dbcrawlerv4.ai.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.ai.metadata.FileUploadMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/10/23 16:19
 * @Desc:
 */
@Component
public class TikaDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
//        Resource resource = new FileSystemResource(filePathStr);
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);
        return tikaDocumentReader.read();
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DOC, DocumentType.DOCX, DocumentType.PDF, DocumentType.PPT, DocumentType.PPTX);
    }

}
