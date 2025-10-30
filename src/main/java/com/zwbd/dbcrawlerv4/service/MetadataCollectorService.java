package com.zwbd.dbcrawlerv4.service;

import com.zwbd.dbcrawlerv4.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.dao.DatabaseDialect;
import com.zwbd.dbcrawlerv4.dao.DialectFactory;
import com.zwbd.dbcrawlerv4.dto.metadata.*;
import com.zwbd.dbcrawlerv4.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.entity.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/12 10:19
 * @Desc:
 */
@Service
public class MetadataCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MetadataCollectorService.class);
    private final DialectFactory dialectFactory;
    private final ExecutorService executorService;
    private final TimeoutConfig timeoutConfig;

    /**
     * Constructor with dependency injection.
     * Spring will automatically inject DialectFactory, ExecutorService and TimeoutConfig bean instances.
     */
    @Autowired
    public MetadataCollectorService(DialectFactory dialectFactory, ExecutorService executorService, TimeoutConfig timeoutConfig) {
        this.dialectFactory = Objects.requireNonNull(dialectFactory, "dialectFactory cannot be null");
        this.executorService = Objects.requireNonNull(executorService, "executorService cannot be null");
        this.timeoutConfig = Objects.requireNonNull(timeoutConfig, "timeoutConfig cannot be null");
    }

    /**
     * 异步采集指定数据源的元数据（使用默认的 AUTO 执行模式）。
     *
     * @param dataBaseInfo 目标数据源。
     * @return 一个 {@link CompletableFuture}，它将在采集完成后返回 {@link DatabaseMetadata} 对象。
     */
    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo) {
        return collectMetadata(dataBaseInfo, ExecutionMode.AUTO);
    }

    public List<Map<String, Object>> executeSQL(DataBaseInfo dataBaseInfo, String sql) {
        DatabaseDialect dialect = dialectFactory.getDialect(dataBaseInfo);
        DataSource dataSource = dialect.createDataSource(dataBaseInfo);
        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);
            List<Map<String, Object>> results = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>(); // 使用LinkedHashMap保持列顺序
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), resultSet.getObject(i));
                }
                results.add(row);
            }
            return results;
        } catch (Exception e) {
            log.error("execute sql error", e);
            return Collections.emptyList();
        }
    }

    /**
     * Asynchronously collect metadata from specified data source with execution mode.
     *
     * @param dataBaseInfo Target data source information.
     * @param mode         Execution mode for metrics calculation (AUTO, FORCE_FULL_SCAN, FORCE_SAMPLE).
     * @return A {@link CompletableFuture} that will return {@link DatabaseMetadata} object when collection completes.
     */
    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo, ExecutionMode mode) {
        // Submit the time-consuming operation to the specified thread pool and return Future immediately
        CompletableFuture<DatabaseMetadata> future = CompletableFuture.supplyAsync(() -> {

            // 1. 获取数据库方言和基本信息
            DatabaseDialect dialect = dialectFactory.getDialect(dataBaseInfo);
            DataSource dataSource = dialect.createDataSource(dataBaseInfo);
            try (Connection connection = dataSource.getConnection()) {
                // 建立连接
                DatabaseMetaData metaData = connection.getMetaData();
                String dbProductName = metaData.getDatabaseProductName();
                String dbProductVersion = metaData.getDatabaseProductVersion();

                // 2. 获取所有catalogs/schemas信息
                List<CatalogMetadata> catalogs = dialect.getCatalogs(connection);

                log.info("get catalogs: {}", catalogs.size());

                // 3. 为每个catalog/schema获取表信息并处理
                List<CatalogMetadata> processedCatalogs = catalogs.stream()
                        .map(catalog -> {
                            try {
                                //设置上下文
                                if (dataBaseInfo.getType() == DataBaseType.SQLSERVER) {

                                } else {
                                    connection.setCatalog(catalog.schemaName());
                                }
                                log.info("handle catalog: {}", catalog);
                                // 获取该schema下的所有表
                                List<TableMetadata> tablesInSchema = dialect.getTablesForSchema(
                                        connection, connection.getCatalog(), catalog.schemaName());

                                // 并行处理每个表，获取其详细元数据和指标
                                List<TableMetadata> processedTables = tablesInSchema.stream()
                                        .map(tableInfo -> {
                                            try {
                                                log.debug("handle table: {}", tableInfo.tableName());
                                                return processTable(connection, dialect, tableInfo, mode);
                                            } catch (SQLException e) {
                                                throw new MetadataCollectionException(
                                                        "handle table '" + catalog.schemaName() + "." + tableInfo.tableName() + "' error ", e);
                                            }
                                        })
                                        .collect(Collectors.toList());
                                log.info("processed tables: {}", processedTables.size());
                                // 返回包含处理后表信息的catalog
                                return catalog.withTables(processedTables);
                            } catch (SQLException e) {
                                throw new MetadataCollectionException(
                                        "handle schema '" + catalog.schemaName() + "' error ", e);
                            }
                        })
                        .collect(Collectors.toList());

                // 4. 组装最终结果
                return new DatabaseMetadata(dbProductName, dbProductVersion, processedCatalogs);

            } catch (SQLException e) {
                throw new MetadataCollectionException("Failed to get database connection or metadata", e);
            }
        }, executorService);

        // Apply timeout configuration to the future
        return future.orTimeout(timeoutConfig.getTaskTimeoutMinutes(), TimeUnit.MINUTES)
                .exceptionally(throwable -> {
                    if (throwable instanceof TimeoutException) {
                        log.error("Metadata collection timed out after {} minutes for database: {}",
                                timeoutConfig.getTaskTimeoutMinutes(), dataBaseInfo.getDatabaseName());
                        throw new MetadataCollectionException(
                                "Metadata collection timed out after " + timeoutConfig.getTaskTimeoutMinutes() + " minutes",
                                throwable);
                    } else {
                        log.error("Metadata collection failed for database: {}", dataBaseInfo.getDatabaseName(), throwable);
                        throw new MetadataCollectionException("Metadata collection failed", throwable);
                    }
                });
    }

    /**
     * 处理单个表的完整流程：获取详情、计算指标、然后将两者合并。
     */
    private TableMetadata processTable(Connection connection, DatabaseDialect dialect, TableMetadata tableInfo, ExecutionMode mode) throws SQLException {
        // 步骤 a: 获取表的详情（包括Schema和数据样本）
        TableMetadata tableDetails = dialect.getTableDetails(connection, tableInfo);

        // 步骤 b: 根据指定的模式计算扩展指标
        Map<String, ExtendedMetrics> metricsMap = dialect.calculateMetricsForTable(connection, tableDetails, mode);

        // 步骤 c: (Enrichment) 将计算出的指标填充回列元数据中
        List<ColumnMetadata> enrichedColumns = tableDetails.columns().stream()
                .map(column -> {
                    ExtendedMetrics metrics = metricsMap.get(column.columnName());
                    return (metrics != null) ? column.withMetrics(Optional.of(metrics)) : column;
                })
                .toList();

        // 步骤 d: 返回一个全新的、包含所有信息的 TableMetadata
        return tableDetails.withColumns(enrichedColumns);
    }

    /**
     * 自定义运行时异常，用于在异步流程中包装 SQLException。
     */
    static class MetadataCollectionException extends RuntimeException {
        public MetadataCollectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
