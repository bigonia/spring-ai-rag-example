package com.zwbd.dbcrawlerv4.ai.tools;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/10/24 15:05
 * @Desc: 自定义工具管理类，记录工具执行记录
 */
@Slf4j
@AllArgsConstructor
public class ToolCallingManagerWrap implements ToolCallingManager {

    private final ToolCallingManager toolCallingManager;

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return toolCallingManager.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, chatResponse);
        if (result.conversationHistory().isEmpty()) {
            return result;
        }
        Message message = result.conversationHistory().get(result.conversationHistory().size() - 1);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
        toolResponseMessage.getResponses().forEach(responseMessage -> {
            String name = responseMessage.name();
            String data = responseMessage.responseData();
            log.info("executeToolCalls name: {}, data: {}", name, data);
        });
        return result;
    }
}
