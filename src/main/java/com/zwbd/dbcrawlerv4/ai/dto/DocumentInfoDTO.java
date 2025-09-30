package com.zwbd.dbcrawlerv4.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:56
 * @Desc:
 */
@Schema(description = "文档摘要信息")
public record DocumentInfoDTO(
        @Schema(description = "文档的唯一ID")
        String documentId,
        @Schema(description = "原始文件名")
        String originalFilename,
        @Schema(description = "来源系统")
        String sourceSystem,
        @Schema(description = "该文档包含的分片数量")
        long chunkCount
) {
}
