package com.zwbd.dbcrawlerv4.document.etl.loader.impl;

import com.zwbd.dbcrawlerv4.document.etl.loader.DocumentLoader;
import com.zwbd.dbcrawlerv4.ai.metadata.BaseMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DatabaseRecordMetadata;
import com.zwbd.dbcrawlerv4.ai.metadata.DocumentType;
import com.zwbd.dbcrawlerv4.datasource.dialect.DataStreamContext;
import com.zwbd.dbcrawlerv4.datasource.entity.DataBaseInfo;
import com.zwbd.dbcrawlerv4.datasource.service.DataBaseInfoService;
import com.zwbd.dbcrawlerv4.datasource.service.MetadataCollectorService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/12/1 16:58
 * @Desc:
 */
@Component
public class DataBaseStreamDocumentLoader implements DocumentLoader {

    @Autowired
    private DataBaseInfoService dataBaseInfoService;

    @Autowired
    private MetadataCollectorService metadataCollectorService;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        DatabaseRecordMetadata databaseRecordMetadata = (DatabaseRecordMetadata) metadata;
        DataBaseInfo info = dataBaseInfoService.findById(Long.parseLong(databaseRecordMetadata.getSourceId())).get().toEntityWithId();
        try (DataStreamContext<String> context =
                     metadataCollectorService.openDataStream(
                             info, databaseRecordMetadata.getSchema(), databaseRecordMetadata.getTable(), databaseRecordMetadata.getTemplate())
        ) {
            return context.getStream().limit(10).map(doc -> new Document(doc,metadata.toMap())).toList();
        }
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DATABASE_STREAM);
    }
}
