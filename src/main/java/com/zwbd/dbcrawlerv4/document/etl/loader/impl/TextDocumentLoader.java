package com.zwbd.dbcrawlerv4.document.etl.loader.impl;

import com.zwbd.dbcrawlerv4.document.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.ai.metadata.FileUploadMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/9/16 11:18
 * @Desc: Text document loader for plain text content
 * 
 * This loader handles plain text content provided directly in the request.
 * It's useful for ingesting text snippets, articles, or any textual content.
 */
@Slf4j
//@Component
public class TextDocumentLoader implements DocumentLoader {

//    @Autowired
    private ResourceLoader resourceLoader;
    
    @Override
    public List<Document> load(BaseMetadata metadata)  {
        log.debug("Loading text document");
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
        //文件资源的元数据
        Map<String, Object> metadataMap = fileUploadMetadata.toMap();
//        List<Document> documents = getDocuments(resource, metadataMap);
        return null;
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.TXT);
    }

}