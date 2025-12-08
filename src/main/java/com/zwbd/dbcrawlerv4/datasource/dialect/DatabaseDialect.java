package com.zwbd.dbcrawlerv4.datasource.dialect;

import com.zaxxer.hikari.HikariDataSource;
import com.zwbd.dbcrawlerv4.common.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.common.exception.CommonException;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.SchemaMetadata;
import com.zwbd.dbcrawlerv4.datasource.dto.metadata.TableMetadata;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.datasource.entity.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:09
 * @Desc:
 */
public abstract class DatabaseDialect {

    @Autowired
    protected TimeoutConfig timeoutConfig;

    /**
     * 采样数据量边界
     */
    protected int SAMPLING_THRESHOLD = 20000;
    /**
     * 采样数量
     */
    protected int SAMPLE_DATA_SIZE = 100;

    /**
     * 返回数据库类型信息，如MySQL
     */
    public abstract DataBaseType getDataBaseType();


    /**
     * 根据连接创建数据源，统一实现
     */
    public DataSource createDataSource(DataBaseInfo dataBaseInfo) {
        try {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setDriverClassName(getDriverClassName());
            dataSource.setJdbcUrl(buildConnectionUrl(dataBaseInfo));
            dataSource.setUsername(dataBaseInfo.getUsername());
            dataSource.setPassword(dataBaseInfo.getPassword());
            return dataSource;
        } catch (Exception e) {
            throw new CommonException("Failed to create data source: " + e.getMessage(), e);
        }
    }


    /**
     * 数据库驱动类名，每种DB类型不同实现
     */
    protected abstract String getDriverClassName();

    /**
     * 根据不同的DB类型构建连接参数
     */
    protected abstract String buildConnectionUrl(DataBaseInfo dataBaseInfo);

    /**
     * 测试与数据库的连接，并检查关键属性如只读状态。
     */
    public abstract boolean testConnection(Connection connection);

    /**
     * 获取单个表的完整Schema信息。这个方法会执行获取列信息、行数等多个相关查询，并将结果组装好。
     */
    public abstract TableMetadata getTableDetails(Connection connection, TableMetadata tableMetadata) throws SQLException;

    /**
     * 为单个表的所有相关列批量计算扩展指标。执行耗时的数据分析。
     */
    public abstract Map<String, ExtendedMetrics> calculateMetricsForTable(Connection connection, TableMetadata tableMetadata, ExecutionMode mode) throws SQLException;

    public abstract List<SchemaMetadata> getSchemas(Connection connection) throws SQLException;

    public abstract List<TableMetadata> getTablesForSchema(Connection connection, String catalogName, String schemaName) throws SQLException;

    /**
     * 【通用实现】获取数据库所有的 Schema (或 Catalog) 名称列表。
     * 用于前端下拉框选择。
     *
     * @param connection 数据库连接
     * @return Schema名称列表
     */
    public List<String> getSchemaNames(Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();

        // MySQL 的 "Database" 对应 JDBC 的 Catalog
        if (getDataBaseType() == DataBaseType.MYSQL) {
            try (ResultSet rs = metaData.getCatalogs()) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_CAT"));
                }
            }
        } else {
            // PostgreSQL, Oracle, SQLServer 的 "Schema" 对应 JDBC 的 Schema
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
        }
        return schemas;
    }

    /**
     * 【通用实现】获取指定 Schema 下的所有表名列表。
     *
     * @param connection 数据库连接
     * @param schemaName Schema名称 (MySQL对应数据库名)
     * @return 表名列表
     */
    public List<String> getTableNames(Connection connection, String schemaName) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();

        String catalog = null;
        String schemaPattern = schemaName;

        // 适配 MySQL：将 schemaName 作为 catalog 参数
        if (getDataBaseType() == DataBaseType.MYSQL) {
            catalog = schemaName;
            schemaPattern = null;
        }
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tableNames.add(rs.getString("TABLE_NAME"));
            }
        }
        return tableNames;
    }

    /**
     * 【通用实现】获取指定 Schema.Table 下的所有列名列表。
     *
     * @param connection 数据库连接
     * @param schemaName Schema名称 (MySQL对应数据库名)
     * @param tableName  表名
     * @return 列名列表
     */
    public List<String> getTableColumns(Connection connection, String schemaName, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();

        String catalog = null;
        String schemaPattern = schemaName;

        // 适配 MySQL：将 schemaName 作为 catalog 参数
        if (getDataBaseType() == DataBaseType.MYSQL) {
            catalog = schemaName;
            schemaPattern = null;
        }

        // columnNamePattern 传 "%" 表示获取所有列
        try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, tableName, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * 【通用模板方法】流式查询全表数据。
     * @return 包含数据的流，流关闭时会自动关闭 ResultSet 和 Statement
     */
    public Stream<Map<String, Object>> streamTableData(Connection connection, String schema, String tableName) throws SQLException {
        String q = getIdentifierQuote();

        // 构建全限定表名
        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ");
        if (schema != null && !schema.isEmpty()) {
            sqlBuilder.append(q).append(schema).append(q).append(".");
        }
        sqlBuilder.append(q).append(tableName).append(q);

        String sql = sqlBuilder.toString();

        // 创建 Statement，设置为只读和向前滚动
        PreparedStatement stmt = connection.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
        );

        // 应用特定数据库的流式设置（如 FetchSize）
        applyStreamingSettings(stmt);

        ResultSet rs = stmt.executeQuery();

        // 转换为 Java Stream
        return convertResultSetToStream(rs, stmt);
    }

    /**
     * 【通用实现】获取标识符引号
     * 根据不同的数据库类型返回对应的引号。
     */
    protected String getIdentifierQuote() {
        if (getDataBaseType() == DataBaseType.MYSQL) {
            return "`";
        }
        // POSTGRESQL, ORACLE, SQLSERVER 通常使用双引号作为标准标识符引号
        return "\"";
    }

    /**
     * 【通用实现】应用流式读取设置
     * 根据不同的数据库类型应用特定的 FetchSize。
     */
    protected void applyStreamingSettings(Statement stmt) throws SQLException {
        if (getDataBaseType() == DataBaseType.MYSQL) {
            // MySQL 必须设置为 Integer.MIN_VALUE 才能逐行流式读取
            stmt.setFetchSize(Integer.MIN_VALUE);
        } else {
            // ORACLE, POSTGRESQL, SQLSERVER 等设置为正整数即可
            // 对于 PostgreSQL，必须关闭自动提交才能使游标生效
            if (getDataBaseType() == DataBaseType.POSTGRESQL) {
                Connection conn = stmt.getConnection();
                if (conn.getAutoCommit()) {
                    conn.setAutoCommit(false);
                }
            }
            stmt.setFetchSize(1000);
        }
    }

    /**
     * 辅助工具：将 ResultSet 转为 Stream
     */
    private Stream<Map<String, Object>> convertResultSetToStream(ResultSet rs, Statement stmt) throws SQLException {
        Iterator<Map<String, Object>> iterator = new Iterator<>() {
            final ResultSetMetaData metaData = rs.getMetaData();
            final int colCount = metaData.getColumnCount();

            // 状态标志：是否已经调用过 rs.next() 预加载了下一行
            boolean didNext = false;
            // 预加载的结果：true表示有数据，false表示结束
            boolean hasNextRow = false;

            @Override
            public boolean hasNext() {
                // 如果还没有预加载过下一行，则执行预加载
                if (!didNext) {
                    try {
                        hasNextRow = rs.next();
                        didNext = true;
                    } catch (SQLException e) {
                        throw new CommonException("Data stream reading error", e);
                    }
                }
                // 直接返回缓存的结果，不再重复调用 rs.next()
                return hasNextRow;
            }

            @Override
            public Map<String, Object> next() {
                // 确保 hasNext() 被调用过
                if (!didNext) {
                    hasNext();
                }
                // 如果没有下一行，抛出标准异常
                if (!hasNextRow) {
                    throw new NoSuchElementException();
                }

                try {
                    // 读取当前游标所在行的数据
                    // 注意：此时 rs 指针已经由 hasNext() 中的 rs.next() 移动到了正确位置
                    Map<String, Object> row = new LinkedHashMap<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }

                    // 重置标志位，强迫下一次 hasNext() 再次调用 rs.next()
                    didNext = false;

                    return row;
                } catch (SQLException e) {
                    throw new CommonException("Data row mapping error", e);
                }
            }
        };

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false
        ).onClose(() -> {
            // 当 Stream 关闭时，自动释放 DB 资源
            try { rs.close(); stmt.close(); } catch (SQLException e) { /* log */ }
        });
    }
}