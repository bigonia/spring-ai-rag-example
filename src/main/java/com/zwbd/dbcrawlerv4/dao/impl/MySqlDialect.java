package com.zwbd.dbcrawlerv4.dao.impl;

import com.zwbd.dbcrawlerv4.dao.DatabaseDialect;
import com.zwbd.dbcrawlerv4.dto.metadata.SchemaMetadata;
import com.zwbd.dbcrawlerv4.dto.metadata.ColumnMetadata;
import com.zwbd.dbcrawlerv4.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.dto.metadata.TableMetadata;
import com.zwbd.dbcrawlerv4.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.entity.ExecutionMode;
import com.zwbd.dbcrawlerv4.exception.CommonException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 针对 MySQL 数据库的 DatabaseDialect 接口实现。
 * 封装了所有与 MySQL 交互的特定 SQL 和逻辑。
 */
@Component
public class MySqlDialect extends DatabaseDialect {


    private static final int TOP_N_CATEGORICAL = 10; // 计算Top-N的N值
    private static final Logger log = LoggerFactory.getLogger(MySqlDialect.class);

    protected String driverClassName = "com.mysql.cj.jdbc.Driver";

    @Override
    public DataBaseType getDataBaseType() {
        return DataBaseType.MYSQL;
    }

    @Override
    protected String getDriverClassName() {
        return driverClassName;
    }

    /**
     * Build MySQL connection URL with parameters
     *
     * @param dataBaseInfo the database info
     * @return the connection URL
     */
    protected String buildConnectionUrl(DataBaseInfo dataBaseInfo) {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("jdbc:mysql://");
        urlBuilder.append(dataBaseInfo.getHost());
        urlBuilder.append(":");
        urlBuilder.append(dataBaseInfo.getPort() != null ? dataBaseInfo.getPort() : 3306);
        if (!ObjectUtils.isEmpty(dataBaseInfo.getDatabaseName())) {
            urlBuilder.append("/");
            urlBuilder.append(dataBaseInfo.getDatabaseName());
        }

        // Add default parameters
        urlBuilder.append("?useSSL=false");
        urlBuilder.append("&allowPublicKeyRetrieval=true");
        urlBuilder.append("&serverTimezone=UTC");
//        urlBuilder.append("&characterEncoding=utf8mb4");
        urlBuilder.append("&useUnicode=true");
        
        // Add timeout parameters to prevent blocking
        urlBuilder.append("&connectTimeout=30000");  // 30 seconds connection timeout
        urlBuilder.append("&socketTimeout=60000");   // 60 seconds socket timeout
        urlBuilder.append("&autoReconnect=true");    // Enable auto reconnect
        urlBuilder.append("&maxReconnects=3");       // Maximum reconnect attempts

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
            // 1. 基础连通性检查 (超时2秒)
            if (!connection.isValid(2)) {
                throw new CommonException("数据库连接无效或已超时。");
            }

            DatabaseMetaData metaData = connection.getMetaData();
            String dbVersion = metaData.getDatabaseProductVersion();

            // 2. 只读状态检查 (通过尝试创建临时表并回滚，这是最可靠的方式)
            boolean canWrite = true;
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false); // 开始事务
                try (Statement statement = connection.createStatement()) {
                    // Set query timeout for connection test
                    statement.setQueryTimeout(10); // 10 seconds timeout for connection test
                    // 创建临时表是一个安全且能有效验证写权限的操作
                    statement.execute("CREATE TEMPORARY TABLE dialect_write_test (id INT)");
                }
            } catch (SQLException e) {
                log.error("error when executing create temporary table", e);
                // 如果创建临时表失败 (例如: 'CREATE command denied to user')，则认为是只读账号
                canWrite = false;
            } finally {
                connection.rollback(); // 无论如何都回滚
                connection.setAutoCommit(originalAutoCommit); // 恢复原始的 autoCommit 状态
            }
//            return !canWrite;
            return true;
        } catch (Exception e) {
            // 4. 捕获任何异常并返回失败结果
            throw new CommonException("连接测试失败: " + e.getMessage());
        }
    }

    @Override
    public TableMetadata getTableDetails(Connection connection, TableMetadata tableInfo) throws SQLException {
        // 1. 获取行数 (从 information_schema 获取的是估算值，但速度快)
//        long rowCount = getEstimatedRowCount(connection, tableInfo.getTableName());

        // 2. 获取列的详细信息
        List<ColumnMetadata> columns = new ArrayList<>();
        String sql = "SELECT column_name, column_type, column_comment, column_key, is_nullable " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Set query timeout to prevent blocking
            ps.setQueryTimeout(timeoutConfig.getMetricsCalculationTimeout());
            ps.setString(1, connection.getCatalog());
            ps.setString(2, tableInfo.tableName());
            log.debug("getTableDetails sql : {}", sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                columns.add(new ColumnMetadata(
                        rs.getString("column_name"),
                        rs.getString("column_type"),
                        Optional.ofNullable(rs.getString("column_comment")),
                        "PRI".equalsIgnoreCase(rs.getString("column_key")),
                        "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                        null// 指标此时为空，待后续计算
                ));
            }
        }
        tableInfo = tableInfo.withColumns(columns);
        Optional<List<Map<String, Object>>> sampleData = getUniformTableSample(connection, tableInfo, SAMPLE_DATA_SIZE); // 获取1000行均匀样本

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
     * 获取一个表的均匀数据样本。
     * * <p><b>核心策略：</b></p>
     * <ol>
     * <li><b>优先使用主键范围采样：</b>如果表存在数值型主键，则通过获取主键的MIN/MAX范围，
     * 在应用层生成随机ID，然后用 `WHERE id IN (...)` 的方式高效获取样本，以保证均匀性。</li>
     * <li><b>降级到LIMIT采样：</b>如果表没有数值型主键，或者数据量过小，则优雅地降级为
     * 简单的 `SELECT * ... LIMIT N` 查询。这种方式虽然不是严格的随机均匀，但对于获取
     * “数据预览”来说，速度最快且完全可用。</li>
     * </ol>
     *
     * @param connection 数据库连接
     * @param tableInfo  表信息
     * @param sampleSize 期望的样本大小
     * @return 包含数据样本的Optional，如果出错或表为空则可能为空
     * @throws SQLException SQL执行异常
     */
    private Optional<List<Map<String, Object>>> getUniformTableSample(Connection connection, TableMetadata tableInfo, int sampleSize) throws SQLException {

        long count = tableInfo.rowCount();
        // 如果表为空或数据量小于等于样本量，直接全量返回
        if (count == 0) {
            return Optional.of(Collections.emptyList());
        }

        // 步骤 1: 尝试寻找一个可用的数值型主键
        Optional<String> primaryKeyColumn = findNumericPrimaryKey(tableInfo);

        String sql;
        if (primaryKeyColumn.isPresent()) {
            // 步骤 2: (理想情况) 执行主键范围采样
            String pkColumn = primaryKeyColumn.get();
            long minId = 0, maxId = 0;

            String rangeSql = String.format("SELECT MIN(`%s`), MAX(`%s`) FROM `%s`", pkColumn, pkColumn, tableInfo.tableName());
            log.debug("getUniformTableSample sql: {}",rangeSql);
            try (Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(30); // 30 seconds timeout for range query
                    try (ResultSet rs = stmt.executeQuery(rangeSql)) {
                        if (rs.next()) {
                            minId = rs.getLong(1);
                            maxId = rs.getLong(2);
                        }
                    }
            }


            if (count <= sampleSize) {
                sql = "SELECT * FROM `" + tableInfo.tableName() + "`";
            } else {
                // Generate random IDs with safety checks
                long range = maxId - minId + 1;
                int targetSampleSize = Math.min(sampleSize * 2, (int) Math.min(range, 10000)); // Limit to prevent infinite loops
                
                Set<Long> randomIds = new HashSet<>();
                Random random = new Random();
                
                // Use a more efficient approach for large ranges
                if (range > targetSampleSize * 10L) {
                    // For large ranges, generate random numbers directly
                    while (randomIds.size() < targetSampleSize) {
                        long randomId = minId + (long) (random.nextDouble() * range);
                        randomIds.add(randomId);
                    }
                } else {
                    // For smaller ranges, use the stream approach with a limit
                    randomIds = random.longs(minId, maxId + 1)
                            .distinct()
                            .limit(targetSampleSize)
                            .boxed()
                            .collect(Collectors.toSet());
                }

                String inClause = randomIds.stream().map(String::valueOf).collect(Collectors.joining(", "));

                sql = String.format("SELECT * FROM `%s` WHERE `%s` IN (%s) LIMIT %d",
                        tableInfo.tableName(), pkColumn, inClause, sampleSize);
            }
        } else {
            // 步骤 3: (降级情况) 执行简单的 LIMIT 采样
            sql = "SELECT * FROM `" + tableInfo.tableName() + "` LIMIT " + sampleSize;
        }

        // 步骤 4: 执行最终的SQL并转换结果
        List<Map<String, Object>> samples = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            // Set query timeout to prevent blocking
            statement.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout()); // 60 seconds timeout
                try (ResultSet rs = statement.executeQuery(sql)) {

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
        }
        return Optional.of(samples);
    }

    /**
     * 辅助方法：查询表的元数据，找到一个数值类型的主键列。
     *
     * @param tableMetadata 表名
     * @return 包含主键列名的Optional，如果找不到则为空
     * @throws SQLException SQL执行异常
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
        // 1. 决策：根据模式和行数决定是否启用采样
        boolean useSampling = false;
        if (mode == ExecutionMode.FORCE_SAMPLE) {
            useSampling = true;
        } else if (mode == ExecutionMode.AUTO && tableMetadata.rowCount() > SAMPLING_THRESHOLD) {
            useSampling = true;
        }

        //样本分析
        if (useSampling) {
            return calculateMetricsFromSample(tableMetadata);
        }

        // 1. 动态构建批量聚合查询
        StringBuilder sqlBuilder = new StringBuilder("SELECT COUNT(*) as total_rows");
        for (ColumnMetadata column : tableMetadata.columns()) {
            String colName = "`" + column.columnName() + "`"; // 使用反引号避免关键字冲突
            sqlBuilder.append(String.format(", SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) as `%s_nulls`", colName, column.columnName()));
            sqlBuilder.append(String.format(", COUNT(DISTINCT %s) as `%s_cardinality`", colName, column.columnName()));

            if (isNumericType(column.dataType())) {
                sqlBuilder.append(String.format(", MIN(%s) as `%s_min`", colName, column.columnName()));
                sqlBuilder.append(String.format(", MAX(%s) as `%s_max`", colName, column.columnName()));
                sqlBuilder.append(String.format(", AVG(%s) as `%s_mean`", colName, column.columnName()));
                sqlBuilder.append(String.format(", STDDEV(%s) as `%s_stddev`", colName, column.columnName()));
            }
        }
        sqlBuilder.append(" FROM `").append(tableMetadata.tableName()).append("`");

        // MySQL没有高效的内置采样语法。对于超大表，随机采样可能非常慢。
        // 生产环境可能需要更复杂的采样策略，如基于主键范围的采样。

        // 警告：MySQL 没有高效的 TABLESAMPLE 语法。
        // 下面的 WHERE RAND() 方法在表非常大时性能可能不佳，因为它可能仍需要全表扫描。
        // 另一种方法是 ORDER BY RAND() LIMIT N，但它需要全表文件排序，成本极高。
        // 在生产环境中，对于超大表（上亿行），可能需要基于主键范围的更复杂采样策略。
        // 这里我们使用一个相对通用的方法作为示例。
//        double samplePercentage = 0.01; // 采样1%，可以配置化
//        sqlBuilder.append(String.format(" WHERE RAND() < %f", samplePercentage));

        // 2. 执行查询并解析结果
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            // Set query timeout to prevent blocking on large tables
            statement.setQueryTimeout(timeoutConfig.getMetadataQueryTimeout()); // 120 seconds timeout for metrics calculation
            log.debug("calculateMetricsForTable sql: {}", sqlBuilder);
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
                                Optional.empty() // 中位数需要单独计算，这里暂不处理
                        ));
                    }

                    //todo  Top-N值需要单独的查询
                    Optional<ExtendedMetrics.CategoricalMetrics> categoricalMetrics = Optional.empty();

                    metricsMap.put(column.columnName(), new ExtendedMetrics(
                            Optional.of(nullRate),
                            Optional.of(uniquenessRate),
                            Optional.of(cardinality),
                            numericMetrics,
                            categoricalMetrics,
                            ExtendedMetrics.MetricSource.FULL_SCAN // 标记来源为样本
                    ));
                }
            }
        }
        return metricsMap;
    }


    /**
     * 全新方法：在内存中基于数据样本计算指标。
     *
     * @param tableMetadata 包含数据样本的元数据对象。
     * @return 指标Map。
     */
    private Map<String, ExtendedMetrics> calculateMetricsFromSample(TableMetadata tableMetadata) {
        List<Map<String, Object>> sampleData = tableMetadata.sampleData().orElse(Collections.emptyList());
        if (sampleData.isEmpty()) {
            return Collections.emptyMap();
        }

        int sampleSize = sampleData.size();
        Map<String, ExtendedMetrics> metricsMap = new HashMap<>();

        // 遍历每一列
        for (ColumnMetadata column : tableMetadata.columns()) {
            String colName = column.columnName();
            List<Object> columnValues = sampleData.stream()
                    .map(row -> row.get(colName))
                    .collect(Collectors.toList());

            // 计算指标...
            long nullCount = columnValues.stream().filter(Objects::isNull).count();
            long cardinality = columnValues.stream().filter(Objects::nonNull).distinct().count();
            double nullRate = (double) nullCount / sampleSize;
            double uniquenessRate = (double) cardinality / sampleSize;

            // ... 可以在这里添加更多如 AVG, MIN, MAX 等的内存计算逻辑 ...

            metricsMap.put(colName, new ExtendedMetrics(
                    Optional.of(nullRate),
                    Optional.of(uniquenessRate),
                    Optional.of(cardinality),
                    Optional.empty(),
                    Optional.empty(), // 数值指标等
                    ExtendedMetrics.MetricSource.SAMPLED // 标记来源为样本
            ));
        }
        return metricsMap;
    }

    /**
     * 辅助方法：判断数据类型是否为数值型。
     * 这是一个简化的实现，可以根据需要进行扩展。
     */
    private boolean isNumericType(String dataType) {
        String lowerType = dataType.toLowerCase();
        return lowerType.contains("int") || lowerType.contains("decimal") ||
                lowerType.contains("double") || lowerType.contains("float") ||
                lowerType.contains("numeric") || lowerType.contains("real");
    }

    @Override
    public List<String> getCatalogNames(Connection connection) throws SQLException {
        List<String> catalogNames = new ArrayList<>();
        // INFORMATION_SCHEMA.SCHEMATA 存储了所有数据库的信息
        String sql = "SHOW DATABASES";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String schemaName = rs.getString(1);
                // Skip system databases
                if (!isSystemDatabase(schemaName)) {
                    catalogNames.add(schemaName);
                }
            }
        }
        return catalogNames;
    }

    @Override
    public List<SchemaMetadata> getSchemas(Connection connection) throws SQLException {
        List<SchemaMetadata> catalogs = new ArrayList<>();

        if (!ObjectUtils.isEmpty(connection.getCatalog())) {
            return List.of(SchemaMetadata.of(connection.getCatalog()));
        }
//        ResultSet rs = connection.getMetaData().getSchemas();
//        while (rs.next()) {
//            String schemaName = rs.getString(1);
//
//            // Skip system databases
//            if (!isSystemDatabase(schemaName)) {
//                catalogs.add(CatalogMetadata.of(null, schemaName));
//            }
//        }
        // In MySQL, schemas are essentially databases
        String sql = "SHOW DATABASES";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String schemaName = rs.getString(1);

                // Skip system databases
                if (!isSystemDatabase(schemaName)) {
                    catalogs.add(SchemaMetadata.of(schemaName));
                }
            }
        }

        return catalogs;
    }

    @Override
    public List<TableMetadata> getTablesForSchema(Connection connection, String catalogName, String schemaName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        // In MySQL, we need to use the schema name as database name
        String sql = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS " +
                "FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? " +
                "AND TABLE_TYPE IN ('BASE TABLE', 'VIEW')";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
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
     * Check if the database name is a system database that should be excluded
     */
    private boolean isSystemDatabase(String databaseName) {
        return "information_schema".equalsIgnoreCase(databaseName) ||
                "performance_schema".equalsIgnoreCase(databaseName) ||
                "mysql".equalsIgnoreCase(databaseName) ||
                "sys".equalsIgnoreCase(databaseName);
    }

}
