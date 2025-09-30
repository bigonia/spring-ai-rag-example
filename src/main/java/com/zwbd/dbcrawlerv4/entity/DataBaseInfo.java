package com.zwbd.dbcrawlerv4.entity;


import com.zwbd.dbcrawlerv4.converter.MapToJsonConverter;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity representing a data source configuration
 * Supports multiple data source types including databases and files
 */
@Entity
@Table(name = "database_info")
@Data
public class DataBaseInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "数据源名称不能为空")
    @Column(nullable = false, unique = true)
    private String name;

    @NotNull(message = "数据源类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataBaseType type;

    private String description;

    // --- 通用核心属性 ---
    private String host;
    private Integer port;
    @Column(name = "db_name") // 'database' may be a reserved keyword
    private String databaseName;
    private String username;
    private String password; // 实际生产中应加密存储

    /**
     * 存储额外的、非通用的连接属性
     * - 对于PostgreSQL: 可能包含 schema
     * - 对于文件: originalFileName, internalFileId 等
     */
    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, String> extraProperties;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


}