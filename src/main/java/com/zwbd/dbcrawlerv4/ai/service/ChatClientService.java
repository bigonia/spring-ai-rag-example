package com.zwbd.dbcrawlerv4.ai.service;

import com.zwbd.dbcrawlerv4.ai.dto.ChatRequest;
import com.zwbd.dbcrawlerv4.ai.dto.StreamEvent;
import com.zwbd.dbcrawlerv4.common.web.GlobalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @Author: wnli
 * @Date: 2025/10/15 14:25
 * @Desc:
 */
@Slf4j
@Service
public class ChatClientService {

    @Qualifier("ragClient")
    @Autowired
    private ChatClient chatClient;

    @Autowired
    private PromptTemplate promptTemplate;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    public List<Message> getHistoryChat(String conversationId) {
        List<Message> messageList = chatMemoryRepository.findByConversationId(conversationId);
        return messageList;
    }


    public Flux<StreamEvent> chat(ChatRequest chatRequest) {
        // 1. 获取包含元数据和内容的完整 ChatResponse 流
        Flux<ChatClientResponse> responseFlux = chatClient
                .prompt()
                .user(chatRequest.query())
                .advisors(a -> a.params(Map.of(ChatMemory.CONVERSATION_ID, chatRequest.sessionId())))
                .advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, chatRequest.toExpression()))
//                .toolContext()
                .stream()
                .chatClientResponse();

        // 2. SESSION_INFO 事件（不变）
        Flux<StreamEvent> sessionInfoStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.SESSION_INFO, Map.of("sessionId", chatRequest.sessionId()))
        );

        // 3. 转换响应流：从 context 提取文档（仅首次发送 CONTEXT），从 ChatResponse 提取文本
        AtomicBoolean contextSent = new AtomicBoolean(false);
        Flux<StreamEvent> responseEvents = responseFlux.flatMap(chatClientResponse -> {

            List<StreamEvent> eventsInThisChunk = new ArrayList<>();

            ChatResponse chatResponse = chatClientResponse.chatResponse();

            // 提取文档，仅一次
            @SuppressWarnings("unchecked")
            List<Document> documents = (List<Document>) chatClientResponse.context()
                    .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
            if (!contextSent.get() && !documents.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.CONTEXT, documents));
                contextSent.set(true);
                log.info("从 context 捕获 {} 个文档", documents.size());
            }

            // 检查当前 chunk 是否包含工具调用的意图
            var output = chatResponse.getResult().getOutput();
            if (output != null && output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                // 提取工具名称和参数
                output.getToolCalls().forEach(toolCall -> {
                    Map<String, Object> toolInfo = Map.of(
                            "toolName", toolCall.name(),
                            "toolType", toolCall.type(),
                            "arguments", toolCall.arguments()
                    );
                    eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.TOOL_EXECUTION, toolInfo));
                    log.info("检测到工具调用: {}", toolCall.name());
                });
            }

            // 提取响应内容
            String content = chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;
            if (content != null && !content.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.TEXT, content));
            }

            return Flux.fromIterable(eventsInThisChunk);
        });

        // 4. END 事件
        Flux<StreamEvent> endStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.END, "Stream finished")
        );

        // 5. 拼接流
        return Flux.concat(sessionInfoStream, responseEvents, endStream);
    }

}
