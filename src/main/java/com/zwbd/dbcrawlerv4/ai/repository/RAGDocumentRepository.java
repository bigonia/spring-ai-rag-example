package com.zwbd.dbcrawlerv4.ai.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentChunkDTO;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentInfoDTO;
import com.zwbd.dbcrawlerv4.ai.dto.RAGFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Desc: RAG 文档混合存储库
 * 统一管理文档的向量数据（通过 VectorStore）和元数据（通过 JdbcTemplate）。
 * Service 层应只调用此类，而不应直接操作 JdbcTemplate。
 */
@Slf4j
@Repository
public class RAGDocumentRepository {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // 批量写入大小
    private static final int BATCH_SIZE = 10;
    // 向量表名 (通常 Spring AI 默认为 vector_store，根据您的实际情况调整)
    private static final String VECTOR_TABLE_NAME = "document_chunks";

    public RAGDocumentRepository(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 1. 向量操作 (Vector Operations)
    // ========================================================================

    /**
     * 批量保存文档向量
     */
    public void save(List<Document> documents) {
        Assert.notEmpty(documents, "Documents list must not be empty");
        int totalSize = documents.size();

        for (int i = 0; i < totalSize; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalSize);
            List<Document> batch = documents.subList(i, end);
            log.debug("Batch saving chunks: {} to {}", i, end - 1);
            vectorStore.add(batch);
            try {
                Thread.sleep(100); // 避免触发数据库或 API 速率限制
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 语义搜索
     */
    public List<Document> search(String query, int topK, List<RAGFilter> filters) {
        Assert.hasText(query, "Query must not be empty");
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);

        if (!CollectionUtils.isEmpty(filters)) {
            buildFilterExpression(filters).ifPresent(builder::filterExpression);
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 删除指定条件的文档（利用 VectorStore 的 Filter 能力）
     */
    public void deleteByExpression(Filter.Expression expression) {
        vectorStore.delete(expression);
    }

    // ========================================================================
    // 2. 元数据管理操作 (Metadata Management - Relational View)
    // ========================================================================

    /**
     * 获取所有“逻辑文档”的摘要列表（基于 document_id 分组）
     * 以前在 Service 层的 SQL 逻辑移动至此
     */
    public List<DocumentInfoDTO> findAllDocumentSummaries() {
        final String sql = """
                SELECT
                    metadata ->> 'document_id' as document_id,
                    MAX(metadata ->> 'original_filename') as original_filename,
                    MAX(metadata ->> 'source_system') as source_system,
                    COUNT(*) as chunk_count
                FROM %s
                WHERE metadata ->> 'document_id' IS NOT NULL
                GROUP BY metadata ->> 'document_id'
                ORDER BY MAX(created_at) DESC
                """.formatted(VECTOR_TABLE_NAME);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentInfoDTO(
                rs.getString("document_id"),
                rs.getString("original_filename"),
                rs.getString("source_system"),
                rs.getLong("chunk_count")
        ));
    }

    /**
     * 根据 document_id 获取所有分片详情
     */
    public List<DocumentChunkDTO> findChunksByDocumentId(String documentId) {
        final String sql = """
                SELECT id, content, metadata
                FROM %s
                WHERE metadata ->> 'document_id' = ?
                ORDER BY (metadata ->> 'chunk_sequence')::int ASC
                """.formatted(VECTOR_TABLE_NAME);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            return new DocumentChunkDTO(
                    rs.getString("id"),
                    rs.getString("content"),
                    parseMetadata(rs.getString("metadata"))
            );
        }, documentId);
    }

    /**
     * 获取单个分片的元数据
     */
    public Optional<Map<String, Object>> findMetadataByChunkId(String chunkId) {
        final String sql = "SELECT metadata FROM " + VECTOR_TABLE_NAME + " WHERE id = ?::uuid";
        try {
            Map<String, Object> metadata = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                    parseMetadata(rs.getString("metadata")), chunkId);
            return Optional.ofNullable(metadata);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 基于 document_id 的物理删除
     */
    public void deleteByDocumentId(String documentId) {
        String sql = "DELETE FROM " + VECTOR_TABLE_NAME + " WHERE metadata ->> 'document_id' = ?";
        jdbcTemplate.update(sql, documentId);
    }

    /**
     * 动态条件删除 (Metadata 匹配)
     */
    public int deleteByMetadataConditions(Map<String, Object> conditions) {
        if (CollectionUtils.isEmpty(conditions)) return 0;

        StringBuilder sql = new StringBuilder("DELETE FROM " + VECTOR_TABLE_NAME + " WHERE 1=1");
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : conditions.entrySet()) {
            // 注意：Key 白名单校验应在 Service 层或此处做更严格处理
            sql.append(" AND metadata ->> ? = ?");
            params.add(entry.getKey());
            params.add(String.valueOf(entry.getValue()));
        }
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    // ========================================================================
    // 3. 辅助方法 (Helpers)
    // ========================================================================

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse metadata JSON", e);
            return Map.of();
        }
    }

    private Optional<Filter.Expression> buildFilterExpression(List<RAGFilter> filters) {
        return filters.stream()
                .map(this::toExpression)
                .reduce((e1, e2) -> new Filter.Expression(Filter.ExpressionType.AND, e1, e2));
    }

    private Filter.Expression toExpression(RAGFilter filter) {
        // Spring AI PGVector 默认行为：不需要 "metadata." 前缀，直接传 key 即可，
        // 但取决于具体 VectorStore 实现。如果之前你的实现能跑通，保持原样。
        // 假设 filter.key() 返回的是 "document_id" 这种纯 key
        String key = filter.key();
        Filter.Key filterKey = new Filter.Key(key);
        Filter.Value filterValue = new Filter.Value(filter.value());

        return switch (filter.operator()) {
            case EQUALS -> new Filter.Expression(Filter.ExpressionType.EQ, filterKey, filterValue);
            case NOT_EQUALS -> new Filter.Expression(Filter.ExpressionType.NE, filterKey, filterValue);
            case IN -> new Filter.Expression(Filter.ExpressionType.IN, filterKey, filterValue);
            case NOT_IN -> new Filter.Expression(Filter.ExpressionType.NIN, filterKey, filterValue);
            case GREATER_THAN -> new Filter.Expression(Filter.ExpressionType.GT, filterKey, filterValue);
            case GREATER_THAN_EQUALS -> new Filter.Expression(Filter.ExpressionType.GTE, filterKey, filterValue);
            case LESS_THAN -> new Filter.Expression(Filter.ExpressionType.LT, filterKey, filterValue);
            case LESS_THAN_EQUALS -> new Filter.Expression(Filter.ExpressionType.LTE, filterKey, filterValue);
        };
    }
}