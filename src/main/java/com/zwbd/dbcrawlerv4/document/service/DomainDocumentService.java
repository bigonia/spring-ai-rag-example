package com.zwbd.dbcrawlerv4.document.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.ai.service.DocumentManagementService;
import com.zwbd.dbcrawlerv4.common.config.CommonConfig;
import com.zwbd.dbcrawlerv4.document.entity.*;
import com.zwbd.dbcrawlerv4.document.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.document.etl.processor.PythonScriptProcessor;
import com.zwbd.dbcrawlerv4.document.etl.reader.DomainDocumentReader;
import com.zwbd.dbcrawlerv4.document.repository.DomainDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.zwbd.dbcrawlerv4.common.config.CommonConfig.objectMapper;
import static com.zwbd.dbcrawlerv4.document.entity.DocConstant.META_KEY_PIPELINE;
import static com.zwbd.dbcrawlerv4.document.entity.DocConstant.PARENT_ID;

/**
 * @Author: wnli
 * @Date: 2025/11/25 10:33
 * @Desc:
 */
@Slf4j
@Service
public class DomainDocumentService {

    @Autowired
    private DomainDocumentRepository domainDocumentRepository;

    @Autowired
    private DocumentManagementService documentManagementService;

    @Autowired
    @Lazy
    private BusinessActionService businessActionService;

    private Map<DocumentType, DocumentLoader> documentLoaders;
    private Map<DocumentType, DomainDocumentReader> documentReaders;

    @Autowired
    public void setDocumentLoaders(List<DocumentLoader> loaderImplementations, List<DomainDocumentReader> readers) {
        documentLoaders = new HashMap<DocumentType, DocumentLoader>();
        if (loaderImplementations != null) {
            for (DocumentLoader loader : loaderImplementations) {
                loader.getSourceType().forEach(type -> documentLoaders.put(type, loader));
            }
        }
        log.info("Initialized Service with loaders: {}", documentLoaders.keySet());
        documentReaders = new HashMap<DocumentType, DomainDocumentReader>();
        if (readers != null) {
            for (DomainDocumentReader reader : readers) {
                reader.getSourceType().forEach(type -> documentReaders.put(type, reader));
            }
        }
        log.info("Initialized Service with readers: {}", documentReaders.keySet());
    }

    /**
     * 初始化领域文档
     */
    @Transactional
    public DomainDocument initDomainDocument(BaseMetadata metadata) {
        DocumentType sourceType = metadata.getDocumentType();
        log.info("Starting ingestion for type: {}", sourceType);

        // 1. 获取加载器
        DocumentLoader loader = documentLoaders.get(sourceType);
        Assert.notNull(loader, "Unsupported source type: " + sourceType);

        // 2. 加载文档
        List<Document> documents = loader.load(metadata);

        // 生成规范化领域文档
        DomainDocument doc = new DomainDocument();
        doc.setSourceId(metadata.getSourceId());
        doc.setDocName(metadata.getSourceName());
        doc.setDocumentType(metadata.getDocumentType());
        // 记录是否流式
        doc.setDocMode(metadata.getDocMode());
        // 记录metadata
        doc.setMetadata(metadata.toMap());
        // 记录文档内容
        doc.setDocument(documents);
        doc.setStatus(DomainDocumentStatus.CREATED);

        DomainDocument saved = domainDocumentRepository.save(doc);
        log.info("Initialized DomainDocument id={} for sourceId={}", saved.getId(), metadata.getSourceId());
        return saved;
    }


    public Stream<DomainDocument.DocumentContext> getStream(Long docId) {
        DomainDocument domainDocument = getDomainDocument(docId);
        if (domainDocument.getDocMode().equals(DocMode.VIRTUAL)) {
            DomainDocumentReader reader = documentReaders.get(domainDocument.getDocumentType());
            Stream<DomainDocument.DocumentContext> baseStream = reader.openContentStream(domainDocument);
            String pipelineJson = (String) domainDocument.getMetadata().get(META_KEY_PIPELINE);
            //数据清洗
            if (StringUtils.hasText(pipelineJson)) {
                List<PipelineConfig> configs = parsePipelineJson(pipelineJson);
                // 简化逻辑：直接遍历并应用，默认都是 Python 脚本
                for (PipelineConfig config : configs) {
                    // 实例化处理器 (注意：Stream close 时需要关闭 Context)
                    PythonScriptProcessor processor = new PythonScriptProcessor(config.getScript());

                    // 使用 flatMap 挂载
                    baseStream = baseStream
                            .flatMap(d -> processor.process(d).stream())
                            .onClose(processor::close);
                }
            }

            return baseStream;
        } else {
            return domainDocument.getContentList().stream();
        }
    }

    /**
     * 创建衍生文档（应用清洗规则）
     *
     * @param parentId     父文档ID
     * @param pythonScript 用户编写的 Python 清洗脚本
     * @return 新生成的衍生文档 ID
     */
    @Transactional
    public Long createDerivedDocument(Long parentId, String pythonScript) {
        DomainDocument parent = domainDocumentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Parent doc not found"));
        // 1. 创建衍生文档对象
        DomainDocument derived = parent.clone();
        // 处理元数据
        derived.getMetadata().put(PARENT_ID, parentId);
        // 2. 保存清洗脚本到 Metadata (无论哪种模式都保存，用于血缘和流式回放)
        List<PipelineConfig> pipelines = new ArrayList<>();
        // 优化：流式场景下，如果父文档已有管道配置，需要继承并合并，形成全量规则链路
        // 这样可以确保当前文档包含完整的处理逻辑，注意：读取端 getStream 需适配此全量策略避免重复执行
        if (parent.getDocMode() == DocMode.VIRTUAL) {
            pipelines.addAll(parsePipelineJson((String) parent.getMetadata().get(META_KEY_PIPELINE)));
        }
        // 追加当前脚本 (列表顺序：父级规则在前，当前规则在后)
        pipelines.add(new PipelineConfig(pythonScript));

        //即使是流式文档，目前系统也会写入几条数据用于预览，所以继续进行清洗操作，以便预览效果
        try (PythonScriptProcessor processor = new PythonScriptProcessor(pythonScript)) {
            List<DomainDocument.DocumentContext> cleanedContent = new ArrayList<>();
            for (DomainDocument.DocumentContext document : parent.getContentList()) {
                List<DomainDocument.DocumentContext> results = processor.process(document);
                cleanedContent.addAll(results);
            }
            derived.setContentList(cleanedContent);
        }
        //写入规则
        try {
            derived.getMetadata().put(META_KEY_PIPELINE, objectMapper.writeValueAsString(pipelines));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize pipeline", e);
        }
//        if (parent.getDocMode() == DocMode.MATERIALIZED) {
//            try (PythonScriptProcessor processor = new PythonScriptProcessor(pythonScript)) {
//                List<Document> cleanedContent = new ArrayList<>();
//                for (Document document : parent.getDocument()) {
//                    List<Document> results = processor.process(document);
//                    cleanedContent.addAll(results);
//                }
//                derived.setDocument(cleanedContent);
//            }
//        }
        // 4. 保存并返回
        return domainDocumentRepository.save(derived).getId();
    }

    private List<PipelineConfig> parsePipelineJson(String pipelineJson) {
        List<PipelineConfig> pipelines = new ArrayList<>();
        if(StringUtils.hasText(pipelineJson)) {
            try {
                List<PipelineConfig> parentPipelines = objectMapper.readValue(
                        pipelineJson,
                        new TypeReference<List<PipelineConfig>>() {
                        }
                );
                pipelines.addAll(parentPipelines);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse parent pipeline", e);
            }
        }
        return pipelines;
    }

    public void triggerVectorization(Long docId) {
        DomainDocument domainDocument = getDomainDocument(docId);
        documentManagementService.ingest(domainDocument);
    }

    /**
     * 获取支持的动作列表
     */
    public Map<BizAction, String> getSupportedActions() {
        return businessActionService.getSupportedActions();
    }

    /**
     * 触发业务动作 (Facade Method)
     * 可以在这里添加领域层面的校验逻辑（如：只有 VIP 租户才能使用 GPT-4）
     */
    public void triggerBusinessAction(Long docId, BizAction actionKey, Map<String, Object> params) {
        DomainDocument doc = getDomainDocument(docId);

        // 1. 领域前置校验
        if (doc.getStatus() == DomainDocumentStatus.PROCESSING) {
            throw new IllegalStateException("文档尚未就绪，无法执行业务操作。当前状态: " + doc.getStatus());
        }

        // 可以在这里扩展策略，例如：
        // if ("GEN_QA".equals(actionKey) && "EXAM".equals(doc.getBusinessType())) { ... }

        // 2. 委托给专业的 Action Service 执行
        log.info("DomainService delegating action [{}] for doc {}", actionKey, docId);
        businessActionService.triggerAction(docId, actionKey, params);
    }

    /**
     * 核心：更新文档内容并标记为 READY
     * 这通常是 ProcessorEngine 处理完毕后的回调
     */
    @Transactional
    public void completeProcessing(Long id, List<Document> processedContent, Map<String, Object> newMetadata) {
        DomainDocument doc = getDomainDocument(id);

        if (processedContent != null) {
            doc.setDocument(processedContent);
        }
        if (newMetadata != null) {
            doc.getMetadata().putAll(newMetadata);
        }

        doc.setStatus(DomainDocumentStatus.PROCESSED); // 处理完成
        domainDocumentRepository.save(doc);
        log.info("DomainDocument id={} processing completed. Status: READY", id);
    }

    /**
     * 注册/更新下游业务状态
     * 供 Business Layer (VectorService, QAService) 调用
     * * @param id 文档ID
     *
     * @param serviceKey 业务标识 (e.g., "VECTOR", "QA", "TAG")
     * @param status     状态 (e.g., "PENDING", "COMPLETED", "FAILED")
     */
    @Transactional
    public void updateDownstreamStatus(Long id, String serviceKey, String status) {
//        DomainDocument doc = getDomainDocument(id);
//
//        // 仅在 JSON Map 中更新对应业务的状态
//        // 注意：这里需要先把 Map 拿出来，put 进去，再 save，以触发 JPA 更新
//        Map<String, String> currentStatus = doc.getDownstreamStatus();
//        if (currentStatus == null) {
//            currentStatus = new java.util.HashMap<>();
//        }
//        currentStatus.put(serviceKey, status);
//        doc.setDownstreamStatus(currentStatus); // 显式 set 确保脏检查生效
//
//        domainDocumentRepository.save(doc);
//        log.info("Updated downstream status for doc id={}: {} -> {}", id, serviceKey, status);
    }

    /**
     * 获取文档详情
     */
    public DomainDocument getDomainDocument(Long id) {
        return domainDocumentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DomainDocument not found: " + id));
    }

    /**
     * 状态流转控制 (通用)
     */
    @Transactional
    public void updateStatus(Long id, DomainDocumentStatus status, String errorMessage) {
        domainDocumentRepository.updateStatus(id, status, errorMessage);
    }

    @Transactional
    public void deleteDomainDocument(Long id) {
        domainDocumentRepository.deleteById(id);
        log.info("Deleted DomainDocument id={}", id);
    }

    public List<DomainDocument> list() {
        return domainDocumentRepository.findAll();
    }
}
