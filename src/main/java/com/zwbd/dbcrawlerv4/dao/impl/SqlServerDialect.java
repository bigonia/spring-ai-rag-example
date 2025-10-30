package com.zwbd.dbcrawlerv4.dao.impl;

import com.zwbd.dbcrawlerv4.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.dao.DatabaseDialect;
import com.zwbd.dbcrawlerv4.dto.metadata.CatalogMetadata;
import com.zwbd.dbcrawlerv4.dto.metadata.ColumnMetadata;
import com.zwbd.dbcrawlerv4.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.dto.metadata.TableMetadata;
import com.zwbd.dbcrawlerv4.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.entity.ExecutionMode;
import com.zwbd.dbcrawlerv4.exception.CommonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL Server database dialect implementation.
 * Encapsulates all SQL Server specific SQL and logic.
 */
@Component
public class SqlServerDialect extends DatabaseDialect {

    private final TimeoutConfig timeoutConfig;

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
        urlBuilder.append(";loginTimeout=").append(timeoutConfig.getConnectionTimeoutSeconds());
        urlBuilder.append(";socketTimeout=").append(timeoutConfig.getSocketTimeoutMillis());

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
                    statement.setQueryTimeout(timeoutConfig.getQueryTimeoutSeconds());
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

    public List<TableMetadata> getTables(Connection connection) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        String sql = "SELECT t.TABLE_NAME, t.TABLE_TYPE, " +
                "ISNULL(ep.value, '') as TABLE_COMMENT, " +
                "ISNULL(p.rows, 0) as TABLE_ROWS " +
                "FROM INFORMATION_SCHEMA.TABLES t " +
                "LEFT JOIN sys.tables st ON t.TABLE_NAME = st.name " +
                "LEFT JOIN sys.partitions p ON st.object_id = p.object_id AND p.index_id IN (0,1) " +
                "LEFT JOIN sys.extended_properties ep ON st.object_id = ep.major_id AND ep.minor_id = 0 AND ep.name = 'MS_Description' " +
                "WHERE t.TABLE_CATALOG = ? AND t.TABLE_SCHEMA = 'dbo'";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Set query timeout for tables query
            ps.setQueryTimeout(timeoutConfig.getQueryTimeoutSeconds());
            ps.setString(1, connection.getCatalog());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableTypeStr = rs.getString("TABLE_TYPE");
                String tableComment = rs.getString("TABLE_COMMENT");
                long tableRows = rs.getLong("TABLE_ROWS");

                TableMetadata.TableType tableType = "VIEW".equalsIgnoreCase(tableTypeStr) ?
                        TableMetadata.TableType.VIEW : TableMetadata.TableType.TABLE;

                tables.add(new TableMetadata(tableName, tableType,
                        Optional.ofNullable(tableComment.isEmpty() ? null : tableComment),
                        tableRows, null, null));
            }
        }
        return tables;
    }

    @Override
    public TableMetadata getTableDetails(Connection connection, TableMetadata tableInfo) throws SQLException {
        // Get column details
        List<ColumnMetadata> columns = new ArrayList<>();
        String sql = "SELECT c.COLUMN_NAME, c.DATA_TYPE, " +
                "ISNULL(ep.value, '') as COLUMN_COMMENT, " +
                "CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END as IS_PRIMARY_KEY, " +
                "CASE WHEN c.IS_NULLABLE = 'YES' THEN 1 ELSE 0 END as IS_NULLABLE " +
                "FROM INFORMATION_SCHEMA.COLUMNS c " +
                "LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE pk ON c.TABLE_NAME = pk.TABLE_NAME AND c.COLUMN_NAME = pk.COLUMN_NAME " +
                "LEFT JOIN sys.columns sc ON OBJECT_NAME(sc.object_id) = c.TABLE_NAME AND sc.name = c.COLUMN_NAME " +
                "LEFT JOIN sys.extended_properties ep ON sc.object_id = ep.major_id AND sc.column_id = ep.minor_id AND ep.name = 'MS_Description' " +
                "WHERE c.TABLE_CATALOG = ? AND c.TABLE_NAME = ? " +
                "ORDER BY c.ORDINAL_POSITION";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Set query timeout for table details query
            ps.setQueryTimeout(timeoutConfig.getQueryTimeoutSeconds());
            ps.setString(1, connection.getCatalog());
            ps.setString(2, tableInfo.tableName());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String columnComment = rs.getString("COLUMN_COMMENT");
                columns.add(new ColumnMetadata(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("DATA_TYPE"),
                        Optional.ofNullable(columnComment.isEmpty() ? null : columnComment),
                        rs.getBoolean("IS_PRIMARY_KEY"),
                        rs.getBoolean("IS_NULLABLE"),
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
     * Get uniform data sample from a table using SQL Server specific methods.
     *
     * @param connection database connection
     * @param tableInfo  table information
     * @param sampleSize desired sample size
     * @return Optional containing data sample
     * @throws SQLException SQL execution exception
     */
    private Optional<List<Map<String, Object>>> getUniformTableSample(Connection connection, TableMetadata tableInfo, int sampleSize) throws SQLException {
        // Try to find a numeric primary key
        Optional<String> primaryKeyColumn = findNumericPrimaryKey(tableInfo);

        String sql;
        if (primaryKeyColumn.isPresent() && tableInfo.rowCount() > sampleSize) {
            // Use TABLESAMPLE for large tables (SQL Server specific)
            double samplePercent = Math.min(100.0, (double) sampleSize * 100.0 / tableInfo.rowCount());
            sql = String.format("SELECT TOP %d * FROM [%s] TABLESAMPLE (%f PERCENT)",
                    sampleSize, tableInfo.tableName(), samplePercent);
        } else {
            // Use simple TOP for small tables or when no primary key
            sql = String.format("SELECT TOP %d * FROM [%s]", sampleSize, tableInfo.tableName());
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
            String colName = "[" + column.columnName() + "]"; // Use square brackets for SQL Server
            String aliasName = column.columnName();

            sqlBuilder.append(String.format(", SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) as [%s_nulls]", colName, aliasName));
            sqlBuilder.append(String.format(", COUNT(DISTINCT %s) as [%s_cardinality]", colName, aliasName));

            if (isNumericType(column.dataType())) {
                sqlBuilder.append(String.format(", MIN(%s) as [%s_min]", colName, aliasName));
                sqlBuilder.append(String.format(", MAX(%s) as [%s_max]", colName, aliasName));
                sqlBuilder.append(String.format(", AVG(CAST(%s AS FLOAT)) as [%s_mean]", colName, aliasName));
                sqlBuilder.append(String.format(", STDEV(%s) as [%s_stddev]", colName, aliasName));
            }
        }
        sqlBuilder.append(" FROM [").append(tableMetadata.tableName()).append("]");

        // Execute query and parse results
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            // Set query timeout for metrics calculation
            statement.setQueryTimeout(timeoutConfig.getMetricsQueryTimeoutSeconds());
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
        return lowerType.contains("int") || lowerType.contains("decimal") ||
                lowerType.contains("float") || lowerType.contains("real") ||
                lowerType.contains("numeric") || lowerType.contains("money") ||
                lowerType.contains("bigint") || lowerType.contains("smallint") ||
                lowerType.contains("tinyint");
    }

    @Override
    public List<CatalogMetadata> getCatalogs(Connection connection) throws SQLException {
        List<CatalogMetadata> catalogs = new ArrayList<>();

        // In SQL Server, we get schemas within the current database
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

                catalogs.add(new CatalogMetadata(schemaName, remarks, List.of()));
            }
        }

//        return catalogs;
        return List.of(CatalogMetadata.of(connection.getCatalog()));

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
            // Set query timeout for schema tables query
            ps.setQueryTimeout(timeoutConfig.getQueryTimeoutSeconds());
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
}