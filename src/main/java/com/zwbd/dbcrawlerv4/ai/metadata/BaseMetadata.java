package com.zwbd.dbcrawlerv4.ai.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/10/21 10:31
 * @Desc:
 */
@Data
//@JsonIgnoreProperties({"fileAddress", "anotherInternalField"})
public abstract class BaseMetadata {

    @JsonIgnore
    @NotBlank(message = "文档ID 不能为空")
    private String documentId = UUID.randomUUID().toString();

    @NotBlank(message = "文档类型 不能为空")
    private DocumentType documentType;

    @NotBlank(message = "来源系统不能为空")
    private String sourceSystem;

    @NotNull
    private Instant createdAt = Instant.now();

    public Map<String, Object> toMap(ObjectMapper objectMapper) {
        return objectMapper.convertValue(this, new TypeReference<>() {
        });
    }

}
