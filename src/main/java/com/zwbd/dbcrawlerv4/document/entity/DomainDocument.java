package com.zwbd.dbcrawlerv4.document.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.TenantId;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/11/25 10:19
 * @Desc: 领域文档 (Domain Model Layer)
 * 核心载体：全生命周期的上下文容器。
 * 它解耦了“物理文件”与“逻辑数据”，作为 Processor Engine 的输入和输出。
 */
@Slf4j
@Data
@Entity
@NoArgsConstructor
@Table(name = "ai_domain_document", indexes = {
        @Index(name = "idx_file_id", columnList = "sourceId"),
        @Index(name = "idx_space_id", columnList = "spaceId")
})
public class DomainDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sourceId;

    private boolean isRoot = true;

    private DocMode docMode;

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    private String docName;

    private DocumentType documentType;

    /**
     * 全局元数据
     */
    @Convert(converter = MapJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 核心操作对象：Spring AI Document 列表
     */
    @Convert(converter = DocumentListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<DocumentContext> contentList = new ArrayList<>();


    @Enumerated(EnumType.STRING)
    private DomainDocumentStatus status;

    /**
     * 已接入业务，上游记录便于统计
     */
    @Convert(converter = BizActionSetJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private Set<BizAction> activeFeatures = new HashSet<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.status == null) {
            this.status = DomainDocumentStatus.CREATED;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public DomainDocument clone(){
        DomainDocument clone = new DomainDocument();
//        clone.setId(this.id);
        clone.setSourceId(this.sourceId);
        clone.setRoot(false);
        clone.setDocMode(this.docMode);
        clone.setDocName(this.docName+"-clone");
        clone.setDocumentType(this.documentType);
        clone.getMetadata().putAll(this.metadata);
        clone.getContentList().addAll(this.contentList);
//        clone.setStatus(this.status);
//        clone.setActiveFeatures(this.activeFeatures);
        clone.setCreatedAt(Instant.now());
        clone.setUpdatedAt(Instant.now());
        return clone;
    }

    public void setDocument(List<Document> contentList) {
        this.contentList = contentList.stream().map(doc -> {
            DocumentContext dto = new DocumentContext();
            dto.setId(doc.getId());
            dto.setText(doc.getText());
            dto.setMetadata(doc.getMetadata());
            return dto;
        }).collect(Collectors.toList());
    }

    public List<Document> getDocument() {
        return contentList.stream().map(doc -> {
            return  new Document(doc.getText(),doc.getMetadata());
        }).collect(Collectors.toList());
    }

    // --- Converters ---

    @Converter
    public static class MapJsonConverter implements AttributeConverter<Map<String, Object>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            try {
                return attribute == null ? "{}" : mapper.writeValueAsString(attribute);
            } catch (IOException e) {
                log.error("Error converting map to JSON", e);
                return "{}";
            }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? new HashMap<>() : mapper.readValue(dbData, new TypeReference<>() {
                });
            } catch (IOException e) {
                log.error("Error converting JSON to map", e);
                return new HashMap<>();
            }
        }
    }

    @Converter
    public static class DocumentListJsonConverter implements AttributeConverter<List<DocumentContext>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(List<DocumentContext> attribute) {
            if (attribute == null) return "[]";
            try {
                // 转换 Document -> JsonDocument -> JSON String
                List<DocumentContext> dtos = attribute.stream().map(doc -> {
                    DocumentContext dto = new DocumentContext();
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
        public List<DocumentContext> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty()) return new ArrayList<>();
            try {
                // 转换 JSON String -> List<JsonDocument>
                List<DocumentContext> dtos = mapper.readValue(dbData, new TypeReference<List<DocumentContext>>() {});

                // 转换 List<JsonDocument> -> List<Document>
                // 手动调用构造函数，绕过 Jackson 的直接实例化问题
                return dtos.stream()
                        .map(dto -> new DocumentContext(dto.getId(), dto.getText() != null ? dto.getText() : "", dto.getMetadata()))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                log.error("Error converting JSON to Document list", e);
                return new ArrayList<>();
            }
        }
    }
    @Converter
    public static class BizActionSetJsonConverter implements AttributeConverter<Set<BizAction>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Set<BizAction> attribute) {
            try {
                return attribute == null ? "[]" : mapper.writeValueAsString(attribute);
            } catch (IOException e) {
                return "[]";
            }
        }

        @Override
        public Set<BizAction> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? new HashSet<>() : mapper.readValue(dbData, new TypeReference<Set<BizAction>>() {
                });
            } catch (IOException e) {
                return new HashSet<>();
            }
        }
    }

    @Converter
    public static class StringSetJsonConverter implements AttributeConverter<Set<String>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Set<String> attribute) {
            try {
                return attribute == null ? "[]" : mapper.writeValueAsString(attribute);
            } catch (IOException e) {
                return "[]";
            }
        }

        @Override
        public Set<String> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? new HashSet<>() : mapper.readValue(dbData, new TypeReference<Set<String>>() {
                });
            } catch (IOException e) {
                return new HashSet<>();
            }
        }
    }

    /**
     * 内部 DTO：用于解决 Spring AI Document 类反序列化时的构造函数校验问题
     * (exactly one of text or media must be specified)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentContext {
        private String id;
        @JsonAlias("text")
        private String text;
        private Map<String, Object> metadata = new HashMap<>();

        public DocumentContext(String text, Map<String, Object> metadata) {
            this.text = text;
            this.metadata = metadata;
        }
    }

}