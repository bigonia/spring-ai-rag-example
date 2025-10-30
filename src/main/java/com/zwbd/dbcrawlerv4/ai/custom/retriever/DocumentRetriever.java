package com.zwbd.dbcrawlerv4.ai.custom.retriever;

import com.zwbd.dbcrawlerv4.ai.dto.ChatRequest;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/28 14:54
 * @Desc: 文档检索
 */
public interface DocumentRetriever {

    List<Document> retrieveDocuments(ChatRequest request);

}
