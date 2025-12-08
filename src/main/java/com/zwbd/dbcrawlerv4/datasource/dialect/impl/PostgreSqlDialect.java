package com.zwbd.dbcrawlerv4.datasource.dialect.impl;

import com.zwbd.dbcrawlerv4.common.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.datasource.dialect.DatabaseDialect;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.SchemaMetadata;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ColumnMetadata;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.TableMetadata;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.datasource.entity.ExecutionMode;
import com.zwbd.dbcrawlerv4.common.exception.CommonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL database dialect implementation.
 * Encapsulates all PostgreSQL specific SQL and logic.
 */
@Component
public class PostgreSqlDialect extends DatabaseDialect {

    private static final int TOP_N_CATEGORICAL = 10;
//    private static final int SAMPLING_THRESHOLD = 100000;

    private final TimeoutConfig timeoutConfig;

    protected String driver = "org.postgresql.Driver";

    @Autowired
    public PostgreSqlDialect(TimeoutConfig timeoutConfig) {
        this.timeoutConfig = timeoutConfig;
    }

    @Override
    public DataBaseType getDataBaseType() {
        return DataBaseType.POSTGRESQL;
    }

    @Override
    protected String getDriverClassName() {
        return driver;
    }


    /**
     * Build PostgreSQL connection URL with parameters
     * 
     * @param dataBaseInfo the database info
     * @return the connection URL
     */
    protected String buildConnectionUrl(DataBaseInfo dataBaseInfo) {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("jdbc:postgresql://");
        urlBuilder.append(dataBaseInfo.getHost());
        urlBuilder.append(":");
        urlBuilder.append(dataBaseInfo.getPort() != null ? dataBaseInfo.getPort() : 5432);
        urlBuilder.append("/");
        urlBuilder.append(dataBaseInfo.getDatabaseName());
        
        // Add default parameters
        urlBuilder.append("?sslmode=disable");
        urlBuilder.append("&connectTimeout=").append(timeoutConfig.getConnectionTimeout());
        urlBuilder.append("&socketTimeout=").append(timeoutConfig.getSocketTimeout());
        
        // Apply extra properties if available
        Map<String, String> extraProperties = dataBaseInfo.getExtraProperties();
        if (extraProperties != null && !extraProperties.isEmpty()) {
            for (Map.Entry<String, String> entry : extraProperties.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                
                if (StringUtils.hasText(value)) {
                    urlBuilder.append("&").append(key).append("=").append(value);
                }
            }
        }
        
        return urlBuilder.toString();
    }

    @Override
    public boolean testConnection(Connection connection) {
        try {
            // 1. Basic connectivity check (timeout 2 seconds)
            if (!connection.isValid(2)) {
                throw new CommonException("Database connection is invalid or timed out.");
            }

            DatabaseMetaData metaData = connection.getMetaData();
            String dbVersion = metaData.getDatabaseProductVersion();

            // 2. Read-only status check (try to create temporary table and rollback)
            boolean canWrite = true;
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false); // Start transaction
                try (Statement statement = connection.createStatement()) {
                // Set query timeout for connection test
                statement.setQueryTimeout(timeoutConfig.getConnectionTestTimeout());
                    // Create temporary table is a safe and effective way to verify write permissions
                    statement.execute("CREATE TEMPORARY TABLE dialect_write_test (id INTEGER)");
                }
            } catch (SQLException e) {
                // If creating temporary table fails, consider it as read-only account
                canWrite = false;
            } finally {
                connection.rollback(); // Rollback anyway
                connection.setAutoCommit(originalAutoCommit); // Restore original autoCommit state
            }
//            return !canWrite;
            return true;
        } catch (Exception e) {
            // 4. Catch any exception and return failure result
            throw new CommonException("Connection test failed: " + e.getMessage());
        }
    }

    /**
     * 【关键修复】获取 PostgreSQL 的 Schema 列表
     * 1. 覆盖父类默认实现，确保不返回 DatabaseName。
     * 2. 过滤掉系统内部 Schema，只返回有意义的 Schema（如 public）。
     */
    @Override
    public List<String> getSchemaNames(Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        // 获取当前连接库中的所有 Schema
        try (ResultSet rs = connection.getMetaData().getSchemas()) {
            while (rs.next()) {
                String schemaName = rs.getString("TABLE_SCHEM");

                // 【过滤逻辑】排除 PG 内部系统 Schema，只保留用户 Schema
                // 如果你需要采集所有 Schema，可以注释掉这个 if 判断
                if (!isSystemSchema(schemaName)) {
                    schemas.add(schemaName);
                }
            }
        }
        return schemas;
    }

    /**
     * 判断是否为 PostgreSQL 系统 Schema
     */
    private boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        return schema.startsWith("pg_")
                || "information_schema".equals(schema);
    }

    /**
     * 获取表名列表
     */
    @Override
    public List<String> getTableNames(Connection connection, String schemaName) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        java.sql.DatabaseMetaData metaData = connection.getMetaData();

        // 容错处理：如果 schemaName 为空，默认查 public
        String schemaPattern = (schemaName == null || schemaName.trim().isEmpty()) ? "public" : schemaName;

        // 扩大表类型支持
        String[] types = {"TABLE", "PARTITIONED TABLE", "FOREIGN TABLE", "MATERIALIZED VIEW", "VIEW"};

        try (ResultSet rs = metaData.getTables(null, schemaPattern, "%", types)) {
            while (rs.next()) {
                tableNames.add(rs.getString("TABLE_NAME"));
            }
        }
        return tableNames;
    }

    @Override
    public TableMetadata getTableDetails(Connection connection, TableMetadata tableInfo) throws SQLException {
        // Get column details
        List<ColumnMetadata> columns = new ArrayList<>();
        String sql = "SELECT c.column_name, c.data_type, " +
                    "COALESCE(col_description(pgc.oid, c.ordinal_position), '') as column_comment, " +
                    "CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END as is_primary_key, " +
                    "CASE WHEN c.is_nullable = 'YES' THEN true ELSE false END as is_nullable " +
                    "FROM information_schema.columns c " +
                    "LEFT JOIN pg_class pgc ON pgc.relname = c.table_name " +
                    "LEFT JOIN information_schema.key_column_usage pk ON c.table_name = pk.table_name AND c.column_name = pk.column_name " +
                    "LEFT JOIN information_schema.table_constraints tc ON pk.constraint_name = tc.constraint_name AND tc.constraint_type = 'PRIMARY KEY' " +
                    "WHERE c.table_catalog = ? AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Set query timeout for table details query
            ps.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout());
            ps.setString(1, connection.getCatalog());
            ps.setString(2, tableInfo.tableName());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String columnComment = rs.getString("column_comment");
                columns.add(new ColumnMetadata(
                        rs.getString("column_name"),
                        rs.getString("data_type"),
                        Optional.ofNullable(columnComment.isEmpty() ? null : columnComment),
                        rs.getBoolean("is_primary_key"),
                        rs.getBoolean("is_nullable"),
                        null // Metrics to be calculated later
                ));
            }
        }
        tableInfo = tableInfo.withColumns(columns);
        Optional<List<Map<String, Object>>> sampleData = getUniformTableSample(connection, tableInfo, SAMPLE_DATA_SIZE);

        return new TableMetadata(
                tableInfo.tableName(),
                tableInfo.tableType(),
                tableInfo.comment(),
                tableInfo.rowCount(),
                columns,
                sampleData
        );
    }

    /**
     * Get uniform data sample from a table using PostgreSQL specific methods.
     * 
     * @param connection database connection
     * @param tableInfo table information
     * @param sampleSize desired sample size
     * @return Optional containing data sample
     * @throws SQLException SQL execution exception
     */
    private Optional<List<Map<String, Object>>> getUniformTableSample(Connection connection, TableMetadata tableInfo, int sampleSize) throws SQLException {
        // Try to find a numeric primary key
        Optional<String> primaryKeyColumn = findNumericPrimaryKey(tableInfo);

        String sql;
        if (primaryKeyColumn.isPresent() && tableInfo.rowCount() > sampleSize) {
            // Use TABLESAMPLE for large tables (PostgreSQL 9.5+)
            double samplePercent = Math.min(100.0, (double) sampleSize * 100.0 / tableInfo.rowCount());
            sql = String.format("SELECT * FROM \"%s\" TABLESAMPLE SYSTEM (%f) LIMIT %d", 
                tableInfo.tableName(), samplePercent, sampleSize);
        } else {
            // Use simple LIMIT for small tables or when no primary key
            sql = String.format("SELECT * FROM \"%s\" LIMIT %d", tableInfo.tableName(), sampleSize);
        }

        List<Map<String, Object>> samples = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                samples.add(row);
            }
        }
        return Optional.of(samples);
    }

    /**
     * Find a numeric primary key column.
     * 
     * @param tableMetadata table metadata
     * @return Optional containing primary key column name
     */
    private Optional<String> findNumericPrimaryKey(TableMetadata tableMetadata) {
        return tableMetadata.columns().stream()
                .filter(c -> c.isPrimaryKey() && isNumericType(c.dataType()))
                .map(ColumnMetadata::columnName)
                .findFirst();
    }

    @Override
    public Map<String, ExtendedMetrics> calculateMetricsForTable(Connection connection, TableMetadata tableMetadata, ExecutionMode mode) throws SQLException {
        if (tableMetadata.columns().isEmpty()) {
            return Collections.emptyMap();
        }

        // Decide whether to use sampling based on mode and row count
        boolean useSampling = false;
        if (mode == ExecutionMode.FORCE_SAMPLE) {
            useSampling = true;
        } else if (mode == ExecutionMode.AUTO && tableMetadata.rowCount() > SAMPLING_THRESHOLD) {
            useSampling = true;
        }

        // Sample analysis
        if (useSampling) {
            return calculateMetricsFromSample(tableMetadata);
        }

        // Build batch aggregation query
        StringBuilder sqlBuilder = new StringBuilder("SELECT COUNT(*) as total_rows");
        for (ColumnMetadata column : tableMetadata.columns()) {
            String colName = "\"" + column.columnName() + "\""; // Use double quotes for PostgreSQL
            sqlBuilder.append(String.format(", SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) as \"%s_nulls\"", colName, column.columnName()));
            sqlBuilder.append(String.format(", COUNT(DISTINCT %s) as \"%s_cardinality\"", colName, column.columnName()));

            if (isNumericType(column.dataType())) {
                sqlBuilder.append(String.format(", MIN(%s) as \"%s_min\"", colName, column.columnName()));
                sqlBuilder.append(String.format(", MAX(%s) as \"%s_max\"", colName, column.columnName()));
                sqlBuilder.append(String.format(", AVG(%s::NUMERIC) as \"%s_mean\"", colName, column.columnName()));
                sqlBuilder.append(String.format(", STDDEV(%s) as \"%s_stddev\"", colName, column.columnName()));
            }
        }
        sqlBuilder.append(" FROM \"").append(tableMetadata.tableName()).append("\"");

        // Execute query and parse results
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            // Set query timeout for metrics calculation
            statement.setQueryTimeout(timeoutConfig.getMetricsCalculationTimeout());
            ResultSet rs = statement.executeQuery(sqlBuilder.toString());
            if (rs.next()) {
                long totalRows = rs.getLong("total_rows");
                if (totalRows == 0) return Collections.emptyMap();

                for (ColumnMetadata column : tableMetadata.columns()) {
                    long nullCount = rs.getLong(column.columnName() + "_nulls");
                    long cardinality = rs.getLong(column.columnName() + "_cardinality");

                    double nullRate = (double) nullCount / totalRows;
                    double uniquenessRate = (double) cardinality / totalRows;

                    Optional<ExtendedMetrics.NumericMetrics> numericMetrics = Optional.empty();
                    if (isNumericType(column.dataType())) {
                        numericMetrics = Optional.of(new ExtendedMetrics.NumericMetrics(
                                Optional.ofNullable(rs.getBigDecimal(column.columnName() + "_min")),
                                Optional.ofNullable(rs.getBigDecimal(column.columnName() + "_max")),
                                Optional.ofNullable(rs.getBigDecimal(column.columnName() + "_mean")),
                                Optional.ofNullable(rs.getBigDecimal(column.columnName() + "_stddev")),
                                Optional.empty() // Median requires separate calculation
                        ));
                    }

                    Optional<ExtendedMetrics.CategoricalMetrics> categoricalMetrics = Optional.empty();

                    metricsMap.put(column.columnName(), new ExtendedMetrics(
                            Optional.of(nullRate),
                            Optional.of(uniquenessRate),
                            Optional.of(cardinality),
                            numericMetrics,
                            categoricalMetrics,
                            ExtendedMetrics.MetricSource.FULL_SCAN
                    ));
                }
            }
        }
        return metricsMap;
    }


    /**
     * Calculate metrics from sample data in memory.
     * 
     * @param tableMetadata table metadata containing sample data
     * @return metrics map
     */
    private Map<String, ExtendedMetrics> calculateMetricsFromSample(TableMetadata tableMetadata) {
        List<Map<String, Object>> sampleData = tableMetadata.sampleData().orElse(Collections.emptyList());
        if (sampleData.isEmpty()) {
            return Collections.emptyMap();
        }

        int sampleSize = sampleData.size();
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();

        // Process each column
        for (ColumnMetadata column : tableMetadata.columns()) {
            String colName = column.columnName();
            List<Object> columnValues = sampleData.stream()
                    .map(row -> row.get(colName))
                    .collect(Collectors.toList());

            // Calculate metrics
            long nullCount = columnValues.stream().filter(Objects::isNull).count();
            long cardinality = columnValues.stream().filter(Objects::nonNull).distinct().count();
            double nullRate = (double) nullCount / sampleSize;
            double uniquenessRate = (double) cardinality / sampleSize;

            metricsMap.put(colName, new ExtendedMetrics(
                    Optional.of(nullRate),
                    Optional.of(uniquenessRate),
                    Optional.of(cardinality),
                    Optional.empty(),
                    Optional.empty(),
                    ExtendedMetrics.MetricSource.SAMPLED
            ));
        }
        return metricsMap;
    }

    /**
     * Helper method to determine if data type is numeric.
     * 
     * @param dataType the data type string
     * @return true if numeric type
     */
    private boolean isNumericType(String dataType) {
        String lowerType = dataType.toLowerCase();
        return lowerType.contains("integer") || lowerType.contains("bigint") ||
                lowerType.contains("smallint") || lowerType.contains("decimal") ||
                lowerType.contains("numeric") || lowerType.contains("real") ||
                lowerType.contains("double") || lowerType.contains("float") ||
                lowerType.contains("serial") || lowerType.contains("bigserial") ||
                lowerType.contains("smallserial");
    }

//    @Override
//    public List<String> getSchemaNames(Connection connection) throws SQLException {
//        List<String> catalogNames = new ArrayList<>();
//        // pg_database 存储了所有数据库的信息
//        String sql = "SELECT datname FROM pg_database " +
//                "WHERE datistemplate = false AND datallowconn = true " +
//                "AND datname NOT IN ('postgres')"; // 'postgres' 库通常用于管理，可以酌情排除
//
//        try (Statement stmt = connection.createStatement();
//             ResultSet rs = stmt.executeQuery(sql)) {
//            while (rs.next()) {
//                catalogNames.add(rs.getString("datname"));
//            }
//        }
//        return catalogNames;
//    }

    @Override
    public List<SchemaMetadata> getSchemas(Connection connection) throws SQLException {
        List<SchemaMetadata> catalogs = new ArrayList<>();

        // In PostgreSQL, we get schemas within the current database
        String sql = "SELECT schema_name, " +
                "CASE WHEN schema_name = current_schema() THEN 'Current schema' ELSE NULL END as remarks " +
                "FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast') " +
                "AND schema_name NOT LIKE 'pg_temp_%' " +
                "AND schema_name NOT LIKE 'pg_toast_temp_%' " +
                "ORDER BY schema_name";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String remarks = rs.getString("remarks");

                catalogs.add(new SchemaMetadata(schemaName, remarks, List.of()));
            }
        }

        return catalogs;
    }

    @Override
    public List<TableMetadata> getTablesForSchema(Connection connection, String catalogName, String schemaName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        
        String sql = "SELECT t.table_name, t.table_type, " +
                    "obj_description(c.oid) as table_comment, " +
                    "COALESCE(s.n_tup_ins + s.n_tup_upd + s.n_tup_del, 0) as estimated_rows " +
                    "FROM information_schema.tables t " +
                    "LEFT JOIN pg_class c ON c.relname = t.table_name " +
                    "LEFT JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema " +
                    "LEFT JOIN pg_stat_user_tables s ON s.relname = t.table_name AND s.schemaname = t.table_schema " +
                    "WHERE t.table_schema = ? " +
                    "AND t.table_type IN ('BASE TABLE', 'VIEW') " +
                    "ORDER BY t.table_name";
        
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Set query timeout for schema tables query
            ps.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout());
            ps.setString(1, schemaName);
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                String tableTypeStr = rs.getString("table_type");
                String tableComment = rs.getString("table_comment");
                Long estimatedRows = rs.getLong("estimated_rows");
                
                TableMetadata.TableType tableType = "VIEW".equalsIgnoreCase(tableTypeStr) ? 
                    TableMetadata.TableType.VIEW : TableMetadata.TableType.TABLE;
                
                tables.add(new TableMetadata(tableName, tableType, 
                    Optional.ofNullable(tableComment != null && !tableComment.isEmpty() ? tableComment : null), 
                    estimatedRows != null ? estimatedRows : 0L, null, null));
            }
        }
        
        return tables;
    }
}