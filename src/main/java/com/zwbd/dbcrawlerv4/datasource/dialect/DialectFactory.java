package com.zwbd.dbcrawlerv4.datasource.dialect;

import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/11 17:47
 * @Desc:
 */
@Component
public class DialectFactory {

    // 缓存数据源：Key = 数据库ID, Value = DataSource
    private final Map<Long, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    // 注册表：Key = 数据库类型, Value = 无状态的方言实现
    private final Map<DataBaseType, DatabaseDialect> dialectRegistry;

    @Autowired
    public DialectFactory(List<DatabaseDialect> dialects) {
        dialectRegistry = dialects.stream()
                .collect(Collectors.toMap(
                        DatabaseDialect::getDataBaseType,
                        Function.identity(),
                        (existing, replacement) -> existing,
                        HashMap::new
                ));
    }

    /**
     * 【核心入口】获取数据库会话。
     * 自动处理 DataSource 的缓存和创建，以及 Dialect 的匹配。
     *
     * @param info 数据库连接信息
     * @return 包含 DataSource 和 Dialect 的会话对象
     */
    public DatabaseSession openSession(DataBaseInfo info) {
        // 1. 获取对应的无状态方言
        DatabaseDialect dialect = dialectRegistry.get(info.getType());
        if (dialect == null) {
            throw new UnsupportedOperationException("没有找到支持 '" + info.getType() + "' 的方言Bean。");
        }

        // 2. 获取或创建 DataSource (线程安全)
        DataSource dataSource = dataSourceCache.computeIfAbsent(info.getId(), k -> {
            // 调用方言的方法来创建数据源配置
            return dialect.createDataSource(info);
        });

        // 3. 组装返回
        return new DatabaseSession(dataSource, dialect);
    }

    /**
     * 手动清除缓存（例如当数据库密码修改后）
     */
    public void invalidateCache(Long dbId) {
        DataSource ds = dataSourceCache.remove(dbId);
        if (ds instanceof AutoCloseable) {
            try {
                ((AutoCloseable) ds).close();
            } catch (Exception e) {
                // log error
            }
        }
    }

}