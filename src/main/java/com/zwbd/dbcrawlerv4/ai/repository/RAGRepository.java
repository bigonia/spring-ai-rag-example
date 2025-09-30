package com.zwbd.dbcrawlerv4.ai.repository;

import com.zwbd.dbcrawlerv4.ai.dto.RAGFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;


/**
 * @Author: wnli
 * @Date: 2025/9/15 17:47
 * @Desc: * RAG 数据访问仓库
 * * 封装了对 VectorStore 的所有操作，包括文档的增、删、查。
 */
@Slf4j
@Repository
public class RAGRepository {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 通过构造函数注入 VectorStore 和 JdbcTemplate
     *
     * @param vectorStore  Spring AI 提供的向量存储抽象
     * @param jdbcTemplate Spring JDBC 核心类，用于执行自定义的SQL操作
     */
    @Autowired
    public RAGRepository(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    int batchSize = 10;

    /**
     * 保存（添加）文档块列表到向量数据库
     *
     * @param documents 待保存的文档列表
     */
    public void save(List<Document> documents) {
        Assert.notEmpty(documents, "Documents list must not be empty");
        int totalSize = documents.size();

        for (int i = 0; i < totalSize; i += batchSize) {
            // 获取当前批次的子列表
            // Math.min确保最后一批不会超出列表范围
            int end = Math.min(i + batchSize, totalSize);
            List<Document> batch = documents.subList(i, end);
            log.info("正在处理批次: 从索引 {} 到 {}, 包含 {} 个文档", i, end - 1, batch.size());
            vectorStore.add(batch);
            try {
                // 在批次之间短暂暂停，以避免速率限制
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
                throw new RuntimeException(e);
            }
        }
        log.info("save to vectorStore success, totalSize: {}", totalSize);
    }

    /**
     * 根据原始文档ID (document_id) 删除所有相关的文档分片。
     * 注意：这里使用 JdbcTemplate 直接执行 SQL 以实现高效的元数据删除，
     * 因为 VectorStore 的 delete API 通常需要分片的主键 (chunk_id)，
     * 先查询再删除效率较低。
     *
     * @param documentId 原始文档的唯一标识
     */
    public void deleteByDocumentId(String documentId) {
        Assert.hasText(documentId, "Document ID must not be empty");
        // 使用 ->> 操作符来查询 JSONB 字段内的文本值
        final String sql = "DELETE FROM document_chunks WHERE metadata ->> 'document_id' = ?";
        this.jdbcTemplate.update(sql, documentId);
    }

    /**
     * 根据用户查询和动态过滤器，查找最相关的文档分片。
     *
     * @param query      用户的查询字符串
     * @param topK       需要返回的最相关结果数量
     * @param RAGFilters 通用的元数据过滤器列表
     * @return 相关的文档列表
     */
    public List<Document> findRelevantDocuments(String query, int topK, List<RAGFilter> RAGFilters) {
        Assert.hasText(query, "Query must not be empty");

        // 如果过滤器为空，则执行简单的相似度搜索
        if (CollectionUtils.isEmpty(RAGFilters)) {
            return this.vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build());
        }

        // 将通用的 Filter DTO 列表翻译成 Spring AI 的 Filter.Expression
        Optional<Filter.Expression> expression = buildFilterExpression(RAGFilters);

        // 构建包含过滤器的高级搜索请求
        SearchRequest request = SearchRequest.builder().query(query)
                .topK(topK)
                .filterExpression(expression.get())
                .build();
        return this.vectorStore.similaritySearch(request);
    }

    /**
     * 将通用的 Filter 列表构建成一个 Spring AI 的 Filter.Expression 对象。
     * 多个过滤器默认使用 AND 逻辑连接。
     *
     * @param RAGFilters 过滤器列表
     * @return Spring AI Filter.Expression
     */
    private Optional<Filter.Expression> buildFilterExpression(List<RAGFilter> RAGFilters) {
        Optional<Filter.Expression> expression = RAGFilters.stream()
                .map(this::toExpression)
                .reduce((expr1, expr2) -> new Filter.Expression(Filter.ExpressionType.AND, expr1, expr2));
        return expression;
    }

    /**
     * 将单个 Filter DTO 翻译成 Spring AI 的 Filter.Expression。
     * 注意：这里的 key 对应的是 document_chunks 表中 metadata 字段 (JSONB) 内的顶级键。
     * Spring AI 的 PgVectorStore 会自动将其翻译成 "metadata ->> 'key'" 这样的SQL语法。
     *
     * @param RAGFilter 单个过滤器
     * @return Spring AI Filter.Expression
     */
    private Filter.Expression toExpression(RAGFilter RAGFilter) {
        // 使用 "metadata." 前缀来明确指示这是对 metadata JSONB 字段的查询
//        String metadataKey = "metadata." + RAGFilter.key();
        String metadataKey =  RAGFilter.key();

        return switch (RAGFilter.operator()) {
            case EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case NOT_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.NE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case IN ->
                    new Filter.Expression(Filter.ExpressionType.IN, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case NOT_IN ->
                    new Filter.Expression(Filter.ExpressionType.NIN, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case GREATER_THAN ->
                    new Filter.Expression(Filter.ExpressionType.GT, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case GREATER_THAN_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.GTE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case LESS_THAN ->
                    new Filter.Expression(Filter.ExpressionType.LT, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case LESS_THAN_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.LTE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
        };
    }
}
