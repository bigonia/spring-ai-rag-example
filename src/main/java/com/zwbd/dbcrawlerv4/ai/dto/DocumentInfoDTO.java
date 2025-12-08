package com.zwbd.dbcrawlerv4.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:56
 * @Desc:
 */
@Schema(description = "文档摘要信息")
public record DocumentInfoDTO(
        String sourceId,
        String sourceName,
        String documentType,
        String sourceSystem,
        long chunkCount
) {
}
