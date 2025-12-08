package com.zwbd.dbcrawlerv4.datasource.service;

import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ExtendedMetrics;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

/**
 * @Author: wnli
 * @Date: 2025/11/28 16:36
 * @Desc: 策略接口：定义指标计算的标准行为
 */
public interface MetricsStrategy {

    /**
     * 获取策略类型
     */
    ExtendedMetrics.MetricSource getSourceType();

    /**
     * 计算单列指标
     *
     * @param dsl   JOOQ 上下文
     * @param table 表定义
     * @param field 列定义
     * @return 扩展指标
     */
    ExtendedMetrics calculate(DSLContext dsl, Table<?> table, Field<?> field);
}
