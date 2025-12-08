package com.zwbd.dbcrawlerv4.datasource.dto;

import com.zwbd.dbcrawlerv4.datasource.dto.metadata.TableMetadata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/24 18:00
 * @Desc:
 */
public record TableViewModel(
        String tableName,
        TableMetadata.TableType tableType,
        Optional<String> comment,
        long rowCount,
        Optional<List<Map<String, String>>> sampleData
) {
}
