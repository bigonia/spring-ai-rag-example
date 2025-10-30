package com.zwbd.dbcrawlerv4.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.dbcrawlerv4.ai.repository.VectorRepository;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentChunkDTO;
import com.zwbd.dbcrawlerv4.ai.dto.DocumentInfoDTO;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:56
 * @Desc:
 */
@Service
public class DocumentManagementService {

    private final JdbcTemplate jdbcTemplate;
    private final VectorRepository vectorRepository;
    private final ObjectMapper objectMapper;

    public DocumentManagementService(JdbcTemplate jdbcTemplate, VectorRepository vectorRepository, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorRepository = vectorRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取知识库中所有文档的摘要列表。
     *
     * @return 文档信息列表
     */
    public List<DocumentInfoDTO> listAllDocuments() {
        // 通过 GROUP BY 查询，聚合每个文档的信息
        final String sql = """
                SELECT
                    metadata ->> 'document_id' as document_id,
                    MAX(metadata ->> 'original_filename') as original_filename,
                    MAX(metadata ->> 'source_system') as source_system,
                    COUNT(*) as chunk_count
                FROM
                    document_chunks
                WHERE
                    metadata ->> 'document_id' IS NOT NULL
                GROUP BY
                    metadata ->> 'document_id'
                ORDER BY
                    MAX(created_at) DESC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentInfoDTO(
                rs.getString("document_id"),
                rs.getString("original_filename"),
                rs.getString("source_system"),
                rs.getLong("chunk_count")
        ));
    }

    /**
     * 根据文档ID获取其所有分片的详细信息。
     *
     * @param documentId 文档的唯一ID
     * @return 该文档的所有分片列表
     */
    public List<DocumentChunkDTO> getDocumentChunks(String documentId) {
        final String sql = """
            SELECT id, content, metadata
            FROM document_chunks
            WHERE metadata ->> 'document_id' = ?
            ORDER BY (metadata ->> 'chunk_sequence')::int ASC
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            try {
                // 将 JSONB 字符串反序列化为 Map
                Map<String, Object> metadata = objectMapper.readValue(
                        rs.getString("metadata"),
                        new TypeReference<>() {}
                );
                return new DocumentChunkDTO(
                        rs.getString("id"),
                        rs.getString("content"),
                        metadata
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse metadata JSONB", e);
            }
        }, documentId);
    }

    /**
     * 更新指定ID的文档分片内容。
     *
     * @param chunkId    要更新的分片的ID
     * @param newContent 新的文本内容
     */
    @Transactional
    public void updateChunkContent(String chunkId, String newContent) {
        Assert.hasText(chunkId, "Chunk ID must not be empty");
        Assert.hasText(newContent, "New content must not be empty");

        // 1. 首先，查询现有分片以保留其元数据
        final String sql = "SELECT metadata FROM document_chunks WHERE id = ?::uuid";
        Map<String, Object> metadata = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("metadata"), new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse metadata JSONB for chunkId: " + chunkId, e);
            }
        }, chunkId);

        if (metadata == null) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " not found.");
        }

        // 2. 创建一个新的 Document 对象，使用旧的元数据和ID，但用新的内容
        Document updatedDocument = new Document(chunkId, newContent, metadata);

        // 3. 调用 save 方法。由于主键冲突，VectorStore 将执行更新（Upsert）操作，并重新计算向量。
        vectorRepository.save(List.of(updatedDocument));
    }

    /**
     * 根据文档ID删除一个文档及其所有分片。
     *
     * @param documentId 要删除的文档的ID
     */
    @Transactional
    public void deleteDocument(String documentId) {
        vectorRepository.deleteByDocumentId(documentId);
    }
}
