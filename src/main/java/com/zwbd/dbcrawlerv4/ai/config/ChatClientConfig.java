package com.zwbd.dbcrawlerv4.ai.config;

import com.zwbd.dbcrawlerv4.ai.rag.processor.DatabaseMetaDataProcessor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: wnli
 * @Date: 2025/9/29 17:00
 * @Desc:
 */
@Configuration
public class ChatClientConfig {

    @Autowired
    VectorStore vectorStore;
    @Autowired
    ChatMemory chatMemory;
    @Autowired
    ChatClient.Builder builder;
    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    @Autowired
    DatabaseMetaDataProcessor databaseMetaDataProcessor;

    @Bean
    public ChatClient initChatClient() {
        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .queryExpander(MultiQueryExpander.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .build())
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.50)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .documentPostProcessors(databaseMetaDataProcessor)
                .build();

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(50)
                .build();

        ChatClient chatClient = builder.defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build() // chat-memory advisor
                ,retrievalAugmentationAdvisor
        ).build();
        return chatClient;
    }

}
