package com.zwbd.dbcrawlerv4.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @Author: wnli
 * @Date: 2025/10/20 17:05
 * @Desc:
 */
@Configuration
public class ModelConfig {

    @Bean
//    @Primary
    @Qualifier("qwenAPI")
    public OpenAiApi qwenAPI(@Value("${spring.ai.qwen.api-key}") String apiKey, @Value("${spring.ai.qwen.base-url}") String url) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(url)
                .build();
    }

    @Bean
    @Primary
    @Qualifier("deepseekAPI")
    public OpenAiApi deepseekAPI(@Value("${spring.ai.deepseek.api-key}") String apiKey, @Value("${spring.ai.deepseek.base-url}") String url) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(url)
                .build();
    }

    @Bean
//    @Primary
    @Qualifier("qwenChatModel")
    public ChatModel qwenChatModel(OpenAiApi openAiApi,
                                   @Value("${spring.ai.qwen.chat.model}") String model,
                                   @Value("${spring.ai.qwen.chat.temperature}") Double temperature
    ) {
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).temperature(temperature).build();
        ChatModel chatModel = OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();
        return chatModel;
    }

    @Bean
    @Primary
    @Qualifier("deepseekChatModel")
    public ChatModel deepseekChatModel(@Qualifier("deepseekAPI") OpenAiApi openAiApi,
                                       @Value("${spring.ai.deepseek.chat.model}") String model,
                                       @Value("${spring.ai.deepseek.chat.temperature}") Double temperature
    ) {
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).temperature(temperature).build();
        ChatModel chatModel = OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();
        return chatModel;
    }

    @Bean
    @Primary
    @Qualifier("qwenEmbeddingModel")
    public EmbeddingModel qwenEmbeddingModel(@Qualifier("qwenAPI") OpenAiApi openAiApi,
                                             @Value("${spring.ai.qwen.embedding.model}") String model) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }


}
