package com.zwbd.dbcrawlerv4.datasource.dialect;

import com.zwbd.dbcrawlerv4.common.exception.CommonException;
import lombok.Getter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * @Author: wnli
 * @Date: 2025/12/1 14:34
 * @Desc: 数据库会话封装。
 * 持有特定的 DataSource 和对应的 Dialect，提供统一的操作入口。
 */
@Getter
public class DatabaseSession {

    /**
     * -- GETTER --
     *  获取原始数据源（仅供特殊场景使用，如手动开启流式上下文）
     */
    private final DataSource dataSource;
    /**
     * -- GETTER --
     *  获取方言（仅供特殊场景使用）
     */
    private final DatabaseDialect dialect;

    public DatabaseSession(DataSource dataSource, DatabaseDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    /**
     * 执行数据库操作（自动管理连接的开启和关闭）。
     * 适用于常规的元数据抓取和指标计算。
     */
    public <R> R execute(SessionCallback<R> callback) {
        try (Connection connection = dataSource.getConnection()) {
            return callback.doInSession(dialect, connection);
        } catch (SQLException e) {
            throw new CommonException("Database execution failed", e);
        }
    }

    @FunctionalInterface
    public interface SessionCallback<R> {
        R doInSession(DatabaseDialect dialect, Connection connection) throws SQLException;
    }
}
