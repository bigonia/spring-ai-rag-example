package com.zwbd.dbcrawlerv4.dao;

import com.zwbd.dbcrawlerv4.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.entity.DataBaseType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/11 17:47
 * @Desc:
 */
@Component
public class DialectFactory {

    private final Map<DataBaseType, DatabaseDialect> dialectMap;

    @Autowired
    public DialectFactory(List<DatabaseDialect> dialects) {
        // 将Bean列表转换为一个Map，Key是数据库名称，Value是Dialect实例。
        // 使用TreeMap并忽略大小写，以增加匹配的健壮性。
        this.dialectMap = dialects.stream()
                .collect(Collectors.toMap(
                        DatabaseDialect::getDataBaseType,
                        Function.identity(),
                        (existing, replacement) -> existing, // 如果有重复的Key，保留第一个
                        TreeMap::new
                ));
    }

    /**
     * 根据数据库连接获取相应的方言Bean。
     *
     * @param info 一个有效的数据库连接。
     * @return 从Spring容器中获取的、匹配的方言Bean实例。
     * @throws SQLException                  如果获取数据库元数据失败。
     * @throws UnsupportedOperationException 如果没有找到支持该数据库的方言Bean。
     */
    public DatabaseDialect getDialect(DataBaseInfo info) {
        DatabaseDialect dialect = dialectMap.get(info.getType());
        if (dialect == null) {
            throw new UnsupportedOperationException("没有找到支持 '" + info.getType() + "' 的方言Bean。");
        }
        return dialect;
    }
}