package com.zwbd.dbcrawlerv4.dao;

import com.zwbd.dbcrawlerv4.config.TimeoutConfig;
import com.zwbd.dbcrawlerv4.dto.metadata.ExtendedMetrics;
import com.zwbd.dbcrawlerv4.dto.metadata.SchemaMetadata;
import com.zwbd.dbcrawlerv4.dto.metadata.TableMetadata;
import com.zwbd.dbcrawlerv4.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.entity.DataBaseType;
import com.zwbd.dbcrawlerv4.entity.ExecutionMode;
import com.zwbd.dbcrawlerv4.exception.CommonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

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

    protected DataSource dataSource = null;

    /**
     * 返回数据库类型信息，如MySQL
     *
     * @return
     */
    public abstract DataBaseType getDataBaseType();


    /**
     * 根据连接创建数据源，统一实现
     * 应该创建为单例对象
     *
     * @param dataBaseInfo
     * @return
     */
    public DataSource createDataSource(DataBaseInfo dataBaseInfo) {
        if (dataSource != null) {
            return dataSource;
        }
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            // Set driver
            dataSource.setDriverClassName(getDriverClassName());
            // Build connection URL
            String url = buildConnectionUrl(dataBaseInfo);
            dataSource.setUrl(url);
            dataSource.setUsername(dataBaseInfo.getUsername());
            dataSource.setPassword(dataBaseInfo.getPassword());
            this.dataSource = dataSource;
            return dataSource;
        } catch (Exception e) {
            throw new CommonException("Failed to create data source: " + e.getMessage(), e);
        }
    }

    /**
     * 数据库驱动类名，每种DB类型不同实现
     *
     * @return
     */
    protected abstract String getDriverClassName();

    /**
     * 根据不同的DB类型构建连接参数
     *
     * @param dataBaseInfo
     * @return
     */
    protected abstract String buildConnectionUrl(DataBaseInfo dataBaseInfo);

    /**
     * 测试与数据库的连接，并检查关键属性如只读状态。
     *
     * @param connection 一个有效的数据库连接.
     * @return 连接测试的结果.
     */
    public abstract boolean testConnection(Connection connection);


    /**
     * 获取单个表的完整Schema信息。这个方法会执行获取列信息、行数等多个相关查询，并将结果组装好。
     *
     * @param connection    The database connection.
     * @param tableMetadata Identifier for the table.
     * @return A TableMetadata object populated with DDL info and row count, but with empty ExtendedMetrics.
     */
    public abstract TableMetadata getTableDetails(Connection connection, TableMetadata tableMetadata) throws SQLException;

    /**
     * 为单个表的所有相关列批量计算扩展指标。执行耗时的数据分析。
     *
     * @param connection    The database connection.
     * @param tableMetadata The table's schema info, used to build the query.
     * @return A map from column name to its calculated metrics.
     */
    public abstract Map<String, ExtendedMetrics> calculateMetricsForTable(Connection connection, TableMetadata tableMetadata, ExecutionMode mode) throws SQLException;

    /**
     * 返回全部数据库信息(catalogs)
     *
     * @param connection
     * @return
     * @throws SQLException
     */
    public abstract List<String> getCatalogNames(Connection connection) throws SQLException;

    /**
     * 返回当前连接模式
     *
     * @param connection The database connection.
     * @return A list of CatalogMetadata objects representing available schemas/catalogs.
     * @throws SQLException if database access error occurs.
     */
    public abstract List<SchemaMetadata> getSchemas(Connection connection) throws SQLException;

    /**
     * Get tables for a specific catalog/schema.
     * This method is used to populate tables within a specific schema context.
     *
     * @param connection  The database connection.
     * @param catalogName The catalog name (can be null for databases that don't support catalogs).
     * @param schemaName  The schema name.
     * @return A list of TableMetadata objects for the specified schema.
     * @throws SQLException if database access error occurs.
     */
    public abstract List<TableMetadata> getTablesForSchema(Connection connection, String catalogName, String schemaName) throws SQLException;
}