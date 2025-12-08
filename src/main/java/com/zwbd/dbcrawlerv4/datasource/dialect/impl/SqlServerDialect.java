package com.zwbd.dbcrawlerv4.datasource.dialect.impl;

import com.zwbd.dbcrawlerv4.common.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.datasource.dialect.DatabaseDialect;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ColumnMetadata;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.SchemaMetadata;
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
 * SQL Server database dialect implementation.
 * Encapsulates all SQL Server specific SQL and logic.
 */
@Component
public class SqlServerDialect extends DatabaseDialect {

//    private final TimeoutConfig timeoutConfig;

    protected String driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

    @Autowired
    public SqlServerDialect(TimeoutConfig timeoutConfig) {
        this.timeoutConfig = timeoutConfig;
    }

    private static final int TOP_N_CATEGORICAL = 10;
//    private static final int SAMPLING_THRESHOLD = 100000;

    @Override
    public DataBaseType getDataBaseType() {
        return DataBaseType.SQLSERVER;
    }

    @Override
    protected String getDriverClassName() {
        return driver;
    }

    /**
     * Build SQL Server connection URL with parameters
     *
     * @param dataBaseInfo the database info
     * @return the connection URL
     */
    protected String buildConnectionUrl(DataBaseInfo dataBaseInfo) {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("jdbc:sqlserver://");
        urlBuilder.append(dataBaseInfo.getHost());
        urlBuilder.append(":");
        urlBuilder.append(dataBaseInfo.getPort() != null ? dataBaseInfo.getPort() : 1433);
        urlBuilder.append(";databaseName=");
        urlBuilder.append(dataBaseInfo.getDatabaseName());

        // Add default parameters
        urlBuilder.append(";encrypt=false");
        urlBuilder.append(";trustServerCertificate=true");
        urlBuilder.append(";loginTimeout=").append(timeoutConfig.getConnectionTimeout());
        urlBuilder.append(";socketTimeout=").append(timeoutConfig.getSocketTimeout() * 1000);

        // Apply extra properties if available
        Map<String, String> extraProperties = dataBaseInfo.getExtraProperties();
        if (extraProperties != null && !extraProperties.isEmpty()) {
            for (Map.Entry<String, String> entry : extraProperties.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if (StringUtils.hasText(value)) {
                    urlBuilder.append(";").append(key).append("=").append(value);
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
                    statement.execute("CREATE TABLE #dialect_write_test (id INT)");
                }
            } catch (SQLException e) {
                // If creating temporary table fails, consider it as read-only account
                canWrite = false;
            } finally {
                connection.rollback(); // Rollback anyway
                connection.setAutoCommit(originalAutoCommit); // Restore original autoCommit state
            }

            boolean isReadOnly = !canWrite;

            // 3. Return success result
            String message = String.format("Connection successful. Account is %s.", isReadOnly ? "read-only" : "read-write");
//            return isReadOnly;
            return true;

        } catch (Exception e) {
            // 4. Catch any exception and return failure result
            throw new CommonException("Connection test failed: " + e.getMessage());
        }
    }

    @Override
    public TableMetadata getTableDetails(Connection connection, TableMetadata tableInfo) throws SQLException {
        // 1. (新) 获取行数
        long rowCount = getApproximateRowCount(connection, tableInfo.tableName());
        // 2. (新) 获取列元数据
        List<ColumnMetadata> columns = getColumnMetadata(connection, tableInfo.tableName());
        // 3. (新) 创建一个包含行数和列的中间对象，用于采样
        TableMetadata metadataWithDetails = new TableMetadata(
                tableInfo.tableName(),
                tableInfo.tableType(),
                tableInfo.comment(),
                rowCount, // 使用新获取的行数
                columns,
                Optional.empty() // 样本稍后填充
        );

        // 4. 获取样本数据
        Optional<List<Map<String, Object>>> sampleData = getUniformTableSample(
                connection, metadataWithDetails, SAMPLE_DATA_SIZE
        );

        // 5. 返回包含所有信息的最终对象
        return new TableMetadata(
                metadataWithDetails.tableName(),
                metadataWithDetails.tableType(),
                metadataWithDetails.comment(),
                metadataWithDetails.rowCount(),
                metadataWithDetails.columns(),
                sampleData // 填充样本
        );
    }

    private List<ColumnMetadata> getColumnMetadata(Connection connection, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();

        // SQL 已修正：
        // 1. 修正了 IS_PRIMARY_KEY 的逻辑 (使用 sys.indexes 和 sys.index_columns)
        // 2. 简化了 COLUMN_COMMENT 的逻辑 (移除 ISNULL)
        String sql = "SELECT " +
                "c.COLUMN_NAME, " +
                "c.DATA_TYPE, " +
                "ep.value as COLUMN_COMMENT, " + // 优化：直接选 value，允许 NULL
                "CASE WHEN i.is_primary_key = 1 AND ic.column_id IS NOT NULL THEN 1 ELSE 0 END as IS_PRIMARY_KEY, " + // 修正：正确的 PK 逻辑
                "CASE WHEN c.IS_NULLABLE = 'YES' THEN 1 ELSE 0 END as IS_NULLABLE " +
                "FROM INFORMATION_SCHEMA.COLUMNS c " +
                "LEFT JOIN sys.columns sc ON OBJECT_NAME(sc.object_id) = c.TABLE_NAME AND sc.name = c.COLUMN_NAME " +
                "LEFT JOIN sys.extended_properties ep ON sc.object_id = ep.major_id AND sc.column_id = ep.minor_id AND ep.name = 'MS_Description' " +
                "LEFT JOIN sys.indexes i ON sc.object_id = i.object_id AND i.is_primary_key = 1 " + // 修正：只关联主键索引
                "LEFT JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id AND sc.column_id = ic.column_id " + // 修正：确保列在 PK 索引中
                "WHERE c.TABLE_CATALOG = ? AND c.TABLE_NAME = ? " +
                "ORDER BY c.ORDINAL_POSITION";

        // 修正：将 ResultSet 也放入 try-with-resources 块
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout());
            ps.setString(1, connection.getCatalog());
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(new ColumnMetadata(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("DATA_TYPE"),
                            Optional.ofNullable(rs.getString("COLUMN_COMMENT")), // 优化：更简洁的 Optional
                            rs.getBoolean("IS_PRIMARY_KEY"),
                            rs.getBoolean("IS_NULLABLE"),
                            null // Metrics to be calculated later
                    ));
                }
            }
        }
        return columns;
    }

    /**
     * 快速获取一个对象（表或索引视图）的近似行数。
     * * 此方法通过查询系统分区表来实现，速度非常快。
     * - 它对 表 (Table) 和 索引视图 (Indexed View) 有效。
     * - 它对 非索引视图 (Non-Indexed View) 无效，并将返回 0，
     * 因为它们没有物理分区。
     * * @param connection 数据库连接
     *
     * @param objectName 表名或视图名
     * @return 近似的行数
     * @throws SQLException
     */
    private long getApproximateRowCount(Connection connection, String objectName) throws SQLException {
        // 这个单一的查询可以同时处理表 (type='U') 和索引视图 (type='V')
        // 因为它们都在 sys.partitions 中有记录 (index_id 0 或 1)。
        // 非索引视图 (type='V') 在 sys.partitions 中没有匹配记录，
        // 因此 SUM() 将返回 NULL，COALESCE 捕获这个 NULL 并返回 0。
        String sql = "SELECT COALESCE(SUM(p.rows), 0) " +
                "FROM sys.objects o " +
                "LEFT JOIN sys.partitions p ON o.object_id = p.object_id AND p.index_id IN (0, 1) " +
                "WHERE o.name = ? AND o.schema_id = SCHEMA_ID(SCHEMA_NAME())"; // 假定在当前用户的默认 schema 中

        long rowCount = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, objectName);

            // 此查询非常快，但设置一个短超时仍然是好习惯
            ps.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout() / 2);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowCount = rs.getLong(1);
                }
            }
        }
        return rowCount;
    }

    /**
     * Get uniform data sample from a table using SQL Server specific methods.
     *
     * @param connection database connection
     * @param tableInfo  table information
     * @param sampleSize desired sample size
     * @return Optional containing data sample
     * @throws SQLException SQL execution exception
     */
    private Optional<List<Map<String, Object>>> getUniformTableSample(Connection connection, TableMetadata tableInfo, int sampleSize) throws SQLException {
        String sql;
        if (tableInfo.tableType() == TableMetadata.TableType.VIEW) {
            //非均匀采样，直接查询
            sql = String.format("SELECT TOP %d * FROM [%s]", sampleSize, tableInfo.tableName());
        } else if (tableInfo.rowCount() > 100000) {
            //快速但有偏的采样,AI建议阈值为一百万
            double samplePercent = Math.min(100.0, (double) sampleSize * 100.0 / tableInfo.rowCount());
            sql = String.format("SELECT TOP %d * FROM [%s] TABLESAMPLE (%f PERCENT)", sampleSize, tableInfo.tableName(), samplePercent);
        } else {
            // 真正的随机采样 (适用于中小型 *表*),(在小表上，NEWID() 的性能开销可以接受)
            sql = String.format("SELECT TOP %d * FROM [%s] ORDER BY NEWID()",
                    sampleSize, tableInfo.tableName());
        }
        if(tableInfo.tableName().equals("V_Project_Bid_Analysis")){
            System.out.println();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        try (Statement statement = connection.createStatement();        ) {
            statement.setQueryTimeout(timeoutConfig.getMetricsCalculationTimeout());
            ResultSet rs = statement.executeQuery(sql);
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
        //特殊的。非索引视图无法快速计算行数，暂时计0，此时必须使用样本
        //优化判定，如果计数为0，但是有样本，那么说明计数异常，为避免问题强制使用样本计数
        if (!tableMetadata.sampleData().orElseGet(List::of).isEmpty() && tableMetadata.rowCount() == 0) {
            useSampling = true;
        }
        // Sample analysis
        if (useSampling) {
            return calculateMetricsFromSample(tableMetadata);
        }
        // Build batch aggregation query
        List<String> selectExpressions = new ArrayList<>(); // 1. 创建一个列表
        for (ColumnMetadata column : tableMetadata.columns()) {
            String colName = "[" + column.columnName() + "]";
            String aliasName = column.columnName();
            // 1. 空值计数
            selectExpressions.add(String.format("SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) as [%s_nulls]", colName, aliasName));
            if (isUnsupportedAggregationType(column.dataType())) {
                continue;
            }
            // 2. (安全) 仅为支持的类型添加聚合
            selectExpressions.add(String.format("APPROX_COUNT_DISTINCT(%s) as [%s_cardinality]", colName, aliasName));
            if (isNumericType(column.dataType())) {
                selectExpressions.add(String.format("MIN(%s) as [%s_min]", colName, aliasName));
                selectExpressions.add(String.format("MAX(%s) as [%s_max]", colName, aliasName));
                selectExpressions.add(String.format("AVG(CAST(%s AS FLOAT)) as [%s_mean]", colName, aliasName));
                selectExpressions.add(String.format("STDEV(%s) as [%s_stddev]", colName, aliasName));
            }
        }
        // 3. 构造最终 SQL
        String sql = "SELECT " +
                String.join(", ", selectExpressions) + // 4. 使用 String.join 自动处理逗号
                " FROM [" + tableMetadata.tableName() + "]";

        // Execute query and parse results
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            // Set query timeout for metrics calculation
            statement.setQueryTimeout(timeoutConfig.getMetricsCalculationTimeout());
            ResultSet rs = statement.executeQuery(sql);
            if (rs.next()) {
//                long totalRows = rs.getLong("total_rows");
//                if (totalRows == 0) return Collections.emptyMap();
                long totalRows = tableMetadata.rowCount();
                for (ColumnMetadata column : tableMetadata.columns()) {
                    long nullCount = rs.getLong(column.columnName() + "_nulls");
                    long cardinality = 0;
                    if (!isUnsupportedAggregationType(column.dataType())) {
                        cardinality = rs.getLong(column.columnName() + "_cardinality");
                    }

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
        return lowerType.contains("int") || lowerType.contains("decimal") ||
                lowerType.contains("float") || lowerType.contains("real") ||
                lowerType.contains("numeric") || lowerType.contains("money") ||
                lowerType.contains("bigint") || lowerType.contains("smallint") ||
                lowerType.contains("tinyint");
    }


    @Override
    public List<String> getSchemaNames(Connection connection) throws SQLException {
        List<String> catalogNames = new ArrayList<>();
        // sys.databases 存储了当前实例上的所有数据库
        String sql = "SELECT name FROM sys.databases " +
                "WHERE name NOT IN ('master', 'tempdb', 'model', 'msdb') AND state_desc = 'ONLINE'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                catalogNames.add(rs.getString("name"));
            }
        }
        return catalogNames;
    }

    @Override
    public List<SchemaMetadata> getSchemas(Connection connection) throws SQLException {
        List<SchemaMetadata> schemas = new ArrayList<>();

        // 这个 SQL 查询与你之前 getCatalogs 中的完全相同
        String sql = "SELECT s.name as schema_name, " +
                "CASE WHEN s.name = SCHEMA_NAME() THEN 'Current schema' ELSE NULL END as remarks " +
                "FROM sys.schemas s " +
                "WHERE s.name NOT IN ('sys', 'INFORMATION_SCHEMA', 'guest', 'db_owner', 'db_accessadmin', " +
                "'db_securityadmin', 'db_ddladmin', 'db_backupoperator', 'db_datareader', 'db_datawriter', " +
                "'db_denydatareader', 'db_denydatawriter') " +
                "ORDER BY s.name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String remarks = rs.getString("remarks");
                schemas.add(new SchemaMetadata(schemaName, remarks, List.of()));
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> getTablesForSchema(Connection connection, String catalogName, String schemaName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        String sql = "SELECT t.TABLE_NAME, t.TABLE_TYPE, " +
                "ISNULL(ep.value, '') as TABLE_COMMENT, " +
                "ISNULL(p.rows, 0) as TABLE_ROWS " +
                "FROM INFORMATION_SCHEMA.TABLES t " +
                "LEFT JOIN sys.tables st ON t.TABLE_NAME = st.name AND t.TABLE_SCHEMA = SCHEMA_NAME(st.schema_id) " +
                "LEFT JOIN sys.partitions p ON st.object_id = p.object_id AND p.index_id IN (0,1) " +
                "LEFT JOIN sys.extended_properties ep ON st.object_id = ep.major_id AND ep.minor_id = 0 AND ep.name = 'MS_Description' " +
                "WHERE t.TABLE_CATALOG = ? AND t.TABLE_SCHEMA = ? " +
                "AND t.TABLE_TYPE IN ('BASE TABLE', 'VIEW') " +
                "ORDER BY t.TABLE_NAME";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout());
            ps.setString(1, catalogName != null ? catalogName : connection.getCatalog());
            ps.setString(2, schemaName);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableTypeStr = rs.getString("TABLE_TYPE");
                String tableComment = rs.getString("TABLE_COMMENT");
                Long tableRows = rs.getLong("TABLE_ROWS");

                TableMetadata.TableType tableType = "VIEW".equalsIgnoreCase(tableTypeStr) ?
                        TableMetadata.TableType.VIEW : TableMetadata.TableType.TABLE;

                tables.add(new TableMetadata(tableName, tableType,
                        Optional.ofNullable(tableComment != null && !tableComment.isEmpty() ? tableComment : null),
                        tableRows != null ? tableRows : 0L, null, null));
            }
        }

        return tables;
    }

    /**
     * 检查数据类型是否为不支持聚合的 LOB 类型 (image, text 等)
     */
    private boolean isUnsupportedAggregationType(String dataType) {
        String lowerType = dataType.toLowerCase();
        return lowerType.equals("image") ||
                lowerType.equals("text") ||
                lowerType.equals("ntext") ||
                lowerType.equals("xml");
    }
}