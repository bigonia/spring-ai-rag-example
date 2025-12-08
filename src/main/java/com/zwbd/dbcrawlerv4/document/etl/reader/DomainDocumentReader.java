package com.zwbd.dbcrawlerv4.document.etl.reader;

import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.document.entity.DomainDocument;
import org.springframework.ai.document.Document;

import java.util.Set;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/12/1 15:57
 * @Desc:
 */
public interface DomainDocumentReader {

    /**
     * 获取文档的流式读取器。
     * 无论底层是静态文本还是DB数据，都转换为 Stream<Document> 供 AI 消费（如嵌入、问答）。
     */
    Stream<DomainDocument.DocumentContext> openContentStream(DomainDocument domainDoc);


    Set<DocumentType> getSourceType();

}
