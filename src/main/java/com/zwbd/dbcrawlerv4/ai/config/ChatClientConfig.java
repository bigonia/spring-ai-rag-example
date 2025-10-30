package com.zwbd.dbcrawlerv4.ai.config;

import com.zwbd.dbcrawlerv4.ai.etl.processor.DatabaseMetaDataProcessor;
import com.zwbd.dbcrawlerv4.ai.tools.CommonTools;
import com.zwbd.dbcrawlerv4.ai.tools.ToolCallingManagerWrap;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @Author: wnli
 * @Date: 2025/9/29 17:00
 * @Desc:
 */
@Slf4j
@Configuration
public class ChatClientConfig implements InitializingBean {

    @Autowired
    VectorStore vectorStore;
    @Autowired
    ChatClient.Builder builder;
    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    @Autowired
    DatabaseMetaDataProcessor databaseMetaDataProcessor;

    @Value("classpath:/prompts/system-prompt.st")
    Resource systemPromptResource;

    @Autowired
    private CommonTools commonTools;

    @Autowired
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Bean
    public PromptTemplate ragSystemPromptTemplate(@Value("classpath:/prompts/system-prompt.st") Resource systemPromptResource) throws IOException {
        PromptTemplate ragSystemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        return ragSystemPromptTemplate;
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient initChatClient(ChatMemory chatMemory) {

        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()

                .queryExpander(MultiQueryExpander.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .numberOfQueries(2)
                        .includeOriginal(true)
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
//                .documentPostProcessors(databaseMetaDataProcessor)

                .build();

        ChatClient chatClient = builder.defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(), // chat-memory advisor
                        retrievalAugmentationAdvisor)
//                .defaultOptions(
//                        ToolCallingChatOptions.builder()
//                                .toolCallbacks(toolCallbackProvider.getToolCallbacks())
//                                .internalToolExecutionEnabled(false)
//                                .build()
//                )
//                .defaultTools(commonTools)
                .defaultTools(new CommonTools())
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
        return chatClient;
    }

//    /**
//     * spring的默认实现，用于包装类增强
//     * @param observationRegistry
//     * @param toolCallbackResolver
//     * @param toolExecutionExceptionProcessor
//     * @return
//     */
//    @Bean
//    public ToolCallingManager defaultToolCallingManager(ObservationRegistry observationRegistry, ToolCallbackResolver toolCallbackResolver,
//                                                        ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
//        return new DefaultToolCallingManager(observationRegistry, toolCallbackResolver, toolExecutionExceptionProcessor);
//    }

//    @Bean
//    @Primary
//    public ToolCallingManager toolCallingManagerWrap(ToolCallingManager defaultToolCallingManager) {
//        return new ToolCallingManagerWrap(defaultToolCallingManager);
//    }

    @Override
    public void afterPropertiesSet() throws Exception {

    }
}
