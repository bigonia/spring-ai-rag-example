package com.zwbd.dbcrawlerv4.ai.dto;

import com.zwbd.dbcrawlerv4.ai.enums.Operator;

/**
 * @Author: wnli
 * @Date: 2025/9/15 17:49
 * @Desc:
 */
public record RAGFilter(
        String key,
        Operator operator,
        Object value
) {}
