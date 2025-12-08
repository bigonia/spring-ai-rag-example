package com.zwbd.dbcrawlerv4.datasource.service;

import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ExtendedMetrics;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/11/28 16:37
 * @Desc:
 * 采样策略实现
 * * 关于“均匀采样”与“性能压力”的权衡说明：
 * 1. 若使用 ORDER BY RAND()：可实现均匀采样，但会导致全表排序，对 CPU/IO 压力极大，严禁用于生产。
 * 2. 若使用 TABLESAMPLE (System)：性能好，但仅支持部分 DB (PG, Oracle)，且 JOOQ 通用性受限。
 * * 本实现采用 【Head Sampling (Limit N)】：
 * - 优势：绝对的零额外压力，查询复杂度 O(1)，仅读取物理存储最靠前的 N 行。
 * - 劣势：样本不具备统计学上的完全均匀性（可能存在时间相关性）。
 * - 适用：AI 数据治理初步画像、Schema 探查。
 */
@Component
public class SampledMetricsStrategy implements MetricsStrategy {

    // 硬限制采样 1000 条，保护数据库
    private static final int SAMPLE_SIZE = 1000;

    @Override
    public ExtendedMetrics.MetricSource getSourceType() {
        return ExtendedMetrics.MetricSource.SAMPLED;
    }

    @Override
    public ExtendedMetrics calculate(DSLContext dsl, Table<?> table, Field<?> field) {
        try {
            // 1. 获取样本数据
            // JOOQ 会根据方言自动生成：
            // MySQL: SELECT col FROM table LIMIT 1000
            // Oracle: SELECT col FROM table WHERE ROWNUM <= 1000
            List<?> samples = dsl.select(field)
                    .from(table)
                    .limit(SAMPLE_SIZE)
                    .fetch(field);

            // 2. 内存计算指标 (Java Stream)
            // 将计算压力转移到应用服务器，释放 DB 资源
            return computeInMemory(samples);

        } catch (Exception e) {
            // 采样失败不应阻断流程，返回空指标
            return emptyMetrics();
        }
    }

    private ExtendedMetrics computeInMemory(List<?> samples) {
        long total = samples.size();
        if (total == 0) {
            return emptyMetrics();
        }

        // 计算空值
        long nonNullCount = samples.stream().filter(Objects::nonNull).count();
        double nullRate = (double) (total - nonNullCount) / total;

        // 计算基数 (Cardinality) - 仅代表样本内的基数
        long distinctCount = samples.stream().filter(Objects::nonNull).distinct().count();
        double uniquenessRate = (double) distinctCount / total;

        // 这里仅计算基础指标，数值分布等复杂指标可在此扩展
        return new ExtendedMetrics(
                Optional.of(nullRate),
                Optional.of(uniquenessRate),
                Optional.of(distinctCount),
                Optional.empty(),
                Optional.empty(),
                ExtendedMetrics.MetricSource.SAMPLED
        );
    }

    private ExtendedMetrics emptyMetrics() {
        return null;
    }
}