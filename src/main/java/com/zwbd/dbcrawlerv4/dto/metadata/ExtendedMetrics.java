package com.zwbd.dbcrawlerv4.dto.metadata;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:40
 * @Desc:
 */
public record ExtendedMetrics(
        Optional<Double> nullRate,
        Optional<Double> uniquenessRate,
        Optional<Long> cardinality,
        Optional<NumericMetrics> numericMetrics,
        Optional<CategoricalMetrics> categoricalMetrics,
        MetricSource source
) {

    public enum MetricSource {
        /**
         * 指标基于对全量数据的扫描计算得出，结果精确。
         */
        FULL_SCAN,
        /**
         * 指标基于对数据样本的分析估算得出，结果可能不精确。
         */
        SAMPLED
    }

    /**
     * 封装数值型指标。
     */
    public record NumericMetrics(
            Optional<BigDecimal> min,
            Optional<BigDecimal> max,
            Optional<BigDecimal> mean,
            Optional<BigDecimal> stdDev, // 标准差
            Optional<BigDecimal> median  // 中位数
    ) {
    }

    /**
     * 封装分类型指标。
     */
    public record CategoricalMetrics(
            List<ValueFrequency> topNValues // Top-N 频繁出现的值
    ) {
    }

    /**
     * 代表一个值及其出现的频率。
     */
    public record ValueFrequency(String value, long count) {
    }

}




