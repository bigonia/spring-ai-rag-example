package com.zwbd.dbcrawlerv4.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/16 12:15
 * @Desc: Swagger configuration for API documentation
 * 
 * This configuration sets up OpenAPI 3.0 documentation for the RAG framework,
 * providing interactive API documentation and testing capabilities.
 */
@Configuration
public class SwaggerConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    /**
     * Configure OpenAPI documentation
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Universal RAG Framework API")
                        .description("A comprehensive RAG (Retrieval-Augmented Generation) framework providing " +
                                "intelligent question-answering capabilities with document ingestion and vector search.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Development Team")
                                .email("dev@zwbd.com")
                                .url("https://github.com/zwbd/universal-rag-framework"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort + contextPath)
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.example.com" + contextPath)
                                .description("Production Server")
                ));
    }
}