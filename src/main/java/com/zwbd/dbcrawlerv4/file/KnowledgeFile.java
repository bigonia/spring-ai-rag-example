package com.zwbd.dbcrawlerv4.file;

import com.zwbd.dbcrawlerv4.file.ProcessingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * 知识库文件元数据实体
 * 用于在数据库中登记和管理上传的文件。
 */
@Data
@Entity
@Table(name = "knowledge_files")
public class KnowledgeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String originalFilename; // 原始文件名

    @Column(nullable = false, unique = true)
    private String storedFilename; // 存储在磁盘上的唯一文件名 (例如 UUID.pdf)

    @Column(nullable = false)
    private String filePath; // 文件在服务器上的完整路径

    @Column(nullable = true)
    private String sourceSystem; // 文档来源 (您之前的字段)

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProcessingStatus processingStatus; // 文件处理状态

    @Column(columnDefinition = "TEXT")
    private String errorMessage; // 记录处理失败的原因

    @Column(nullable = false)
    private Instant createdAt; // 上传时间


}