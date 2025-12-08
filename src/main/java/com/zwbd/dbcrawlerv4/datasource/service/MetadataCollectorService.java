package com.zwbd.dbcrawlerv4.datasource.service;

import com.zwbd.dbcrawlerv4.common.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.common.exception.CommonException;
import com.zwbd.dbcrawlerv4.datasource.dialect.DataStreamContext;
import com.zwbd.dbcrawlerv4.datasource.dialect.DatabaseDialect;
import com.zwbd.dbcrawlerv4.datasource.dialect.DatabaseSession;
import com.zwbd.dbcrawlerv4.datasource.dialect.DialectFactory;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.*;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.datasource.entity.ExecutionMode;
import com.zwbd.dbcrawlerv4.utils.TemplateRenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/12 10:19
 * @Desc:
 */
@Slf4j
@Service
public class MetadataCollectorService {

    @Autowired
    private DialectFactory dialectFactory;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private TimeoutConfig timeoutConfig;
    @Autowired
    private TemplateRenderService templateRenderService;

    /**
     * 异步采集指定数据源的元数据（使用默认的 AUTO 执行模式）。
     *
     * @param dataBaseInfo 目标数据源。
     * @return 一个 {@link CompletableFuture}，它将在采集完成后返回 {@link DatabaseMetadata} 对象。
     */
    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo) {
        return collectMetadata(dataBaseInfo, ExecutionMode.AUTO);
    }

    public List<String> getSchemas(DataBaseInfo dbInfo) {
        DatabaseSession session = dialectFactory.openSession(dbInfo);
        return session.execute(DatabaseDialect::getSchemaNames);
    }

    public List<String> getTables(DataBaseInfo dbInfo, String schema) {
        DatabaseSession session = dialectFactory.openSession(dbInfo);
        return session.execute((dialect, connection) -> dialect.getTableNames(connection, schema));
    }

    public List<String> getColumns(DataBaseInfo dbInfo, String schema, String tableName) {
        DatabaseSession session = dialectFactory.openSession(dbInfo);
        return session.execute((dialect, connection) -> dialect.getTableColumns(connection, schema, tableName));
    }

    public DataStreamContext<String> openDataStream(DataBaseInfo dbInfo, String schema, String tableName, String template) {
        try (DataStreamContext<Map<String, Object>> context = openDataStream(dbInfo, schema, tableName)){
            Stream<String> stream = context.getStream().map(stringObjectMap -> templateRenderService.render(template, stringObjectMap));
            return new DataStreamContext<>(stream, context);
        }
    }

    public DataStreamContext<Map<String, Object>> openDataStream(DataBaseInfo dbInfo, String schema, String tableName) {
        DatabaseSession session = dialectFactory.openSession(dbInfo);
        Connection connection = null;
        try {
            connection = session.getDataSource().getConnection();
            Stream<Map<String, Object>> stream = session.getDialect().streamTableData(connection, schema, tableName);
            return new DataStreamContext<>(stream, () -> {
                System.out.println("Stream consumed. Closing connection.");
//                finalConnection.close();
            });
        } catch (Exception e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ex) {
                    log.error("Failed to close connection.", ex);
                }
            }
            throw new CommonException("Failed to open data stream", e);
        }
    }

    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo, ExecutionMode mode) {
        CompletableFuture<DatabaseMetadata> future = CompletableFuture.supplyAsync(() -> {

            // 1. 获取会话 (自动处理 DataSource 和 Dialect 的匹配)
            DatabaseSession databaseSession = dialectFactory.openSession(dataBaseInfo);

            // 2. 在会话中执行 (自动管理 Connection 关闭，无需手动 try-catch-close)
            return databaseSession.execute((dialect, connection) -> {
                // 获取基础信息
                DatabaseMetaData metaData = connection.getMetaData();
                String dbProductName = metaData.getDatabaseProductName();
                String dbProductVersion = metaData.getDatabaseProductVersion();

                // 2. 获取所有catalogs
                List<String> catalogNames = dialect.getSchemaNames(connection);
                log.info("get catalogs: {}", catalogNames);
                List<SchemaMetadata> processedCatalogs = new ArrayList<>(List.of());
                // 开始遍历
                catalogNames.forEach(catalog -> {
                    try {
                        //设置上下文
                        connection.setCatalog(catalog);
                        log.info("handle catalog: {}", catalog);
                        //获取全部schema
                        List<SchemaMetadata> schemas = dialect.getSchemas(connection);
                        log.info("get schemas: {}", schemas);
                        schemas.forEach(schemaMetadata -> {
                            try {
                                // 获取该schema下的所有表
                                List<TableMetadata> tablesInSchema = dialect.getTablesForSchema(
                                        connection, catalog, schemaMetadata.schemaName());
                                log.info("get tables in schema: {}", tablesInSchema);
                                // 处理每个表，获取其详细元数据和指标!!!!禁止并发执行
                                List<TableMetadata> processedTables = tablesInSchema.stream()
                                        .map(tableInfo -> {
                                            try {
                                                log.debug("handle table: {}", tableInfo.tableName());
                                                return processTable(databaseSession.getDataSource(), dialect, catalog, tableInfo, mode);
                                            } catch (SQLException e) {
                                                log.error("handle table {} error", tableInfo, e);
                                                return null;
                                            }
                                        })
                                        .filter(Objects::nonNull)
                                        .toList();
                                log.info("processed tables: {}", processedTables.size());
                                // 返回包含处理后表信息的catalog
                                processedCatalogs.add(schemaMetadata.withTables(processedTables));
                            } catch (SQLException e) {
                                throw new RuntimeException("handle schema '" + schemaMetadata.getSchemaName() + "' error ", e);
                            }
                        });
                    } catch (SQLException e) {
                        // 单个 Schema 失败不影响其他 Schema
                        log.error("Error collecting metadata for schema/catalog: {}", catalog, e);
                        throw new RuntimeException("Handle schema '" + catalog + "' error", e);
                    }
                });
                return new DatabaseMetadata(dbProductName, dbProductVersion, processedCatalogs);
            });
        }, executorService);

        // 超时与异常处理 (保持原有逻辑)
        return future.orTimeout(timeoutConfig.getTaskTimeoutMinutes(), TimeUnit.MINUTES)
                .exceptionally(throwable -> {
                    if (throwable instanceof TimeoutException) {
                        log.error("Metadata collection timed out for DB: {}", dataBaseInfo.getDatabaseName());
                        throw new RuntimeException("Task timed out", throwable);
                    } else {
                        // 解包 CompletionException
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        log.error("Metadata collection failed for DB: {}", dataBaseInfo.getDatabaseName(), cause);
                        throw new RuntimeException("Metadata collection failed: " + cause.getMessage(), cause);
                    }
                });
    }

    /**
     * 处理单个表的完整流程：获取详情、计算指标、然后将两者合并。
     */
    private TableMetadata processTable(DataSource dataSource, DatabaseDialect dialect, String catalog, TableMetadata tableInfo, ExecutionMode mode) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // 设置此连接的上下文
            connection.setCatalog(catalog);
            // 步骤 a: 获取表的详情（包括Schema和数据样本）
            TableMetadata tableDetails = dialect.getTableDetails(connection, tableInfo);
            log.info("process table: {} rowCount: {}", tableDetails.tableName(), tableDetails.rowCount());
            // 步骤 b: 根据指定的模式计算扩展指标，跳过空表
            Map<String, ExtendedMetrics> metricsMap;
            if (tableDetails.rowCount() == 0 && tableDetails.sampleData().orElseGet(List::of).isEmpty()) {
                metricsMap = new HashMap<>();
            } else {
                metricsMap = dialect.calculateMetricsForTable(connection, tableDetails, mode);
            }
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
    }


}
