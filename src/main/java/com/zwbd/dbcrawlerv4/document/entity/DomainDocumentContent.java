package com.zwbd.dbcrawlerv4.document.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/12/8 16:47
 * @Desc:
 */
@Slf4j
@Data
@Entity
@NoArgsConstructor
@Table(name = "ai_domain_document_content", indexes = {
        @Index(name = "idx_doc_id", columnList = "documentId", unique = true) // 1:1 关系，设为唯一索引
})
public class DomainDocumentContent {

    @Id
    private Long id; // 建议直接使用 DomainDocument 的 ID 作为主键，或者使用自增 ID + documentId

    @Column(nullable = false, unique = true)
    private Long documentId; // 关联主表 ID

    /**
     * 这里存放原来主表里的重型数据
     * 依然沿用你之前的 Converter
     */
    @Convert(converter = DocumentListJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT") // 确保足够大，MySQL 中 TEXT 可能不够
    private List<Context> contents = new ArrayList<>();

    public DomainDocumentContent(Long documentId, List<Context> contents) {
        this.documentId = documentId;
        this.contents = contents;
    }

    @Converter
    public static class DocumentListJsonConverter implements AttributeConverter<List<Context>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(List<Context> attribute) {
            if (attribute == null) return "[]";
            try {
                // 转换 Document -> JsonDocument -> JSON String
                List<DomainDocument.DocumentContext> dtos = attribute.stream().map(doc -> {
                    DomainDocument.DocumentContext dto = new DomainDocument.DocumentContext();
                    dto.setId(doc.getId());
                    dto.setText(doc.getText());
                    dto.setMetadata(doc.getMetadata());
                    return dto;
                }).collect(Collectors.toList());
                return mapper.writeValueAsString(dtos);
            } catch (IOException e) {
                log.error("Error converting Document list to JSON", e);
                return "[]";
            }
        }

        @Override
        public List<Context> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty()) return new ArrayList<>();
            try {
                // 转换 JSON String -> List<JsonDocument>
                List<DomainDocument.DocumentContext> dtos = mapper.readValue(dbData, new TypeReference<List<DomainDocument.DocumentContext>>() {});

                // 转换 List<JsonDocument> -> List<Document>
                // 手动调用构造函数，绕过 Jackson 的直接实例化问题
                return dtos.stream()
                        .map(dto -> new Context(dto.getId(), dto.getText() != null ? dto.getText() : "", dto.getMetadata()))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                log.error("Error converting JSON to Document list", e);
                return new ArrayList<>();
            }
        }
    }

}
