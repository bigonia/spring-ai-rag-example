package com.zwbd.dbcrawlerv4.dto.metadata;

import lombok.With;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/19 11:08
 * @Desc: Represents catalog/schema level metadata in database hierarchy.
 * In database systems, catalogs and schemas provide logical grouping of tables.
 * - Catalog: Top-level container (database name in MySQL, database name in SQL Server)
 * - Schema: Secondary-level container (schema name in PostgreSQL/SQL Server, database name in MySQL)
 */
@With
public record CatalogMetadata(
        String schemaName,      // Schema name within the catalog
        String remarks,         // Optional description/comments for the schema
        List<TableMetadata> tables  // Tables contained within this schema
) {

    /**
     * Create a CatalogMetadata with minimal information
     */
    public static CatalogMetadata of(String schemaName) {
        return new CatalogMetadata(schemaName, null, List.of());
    }

    /**
     * Create a CatalogMetadata with tables
     */
    public static CatalogMetadata of(String schemaName, List<TableMetadata> tables) {
        return new CatalogMetadata(schemaName, null, tables);
    }


    public String getSchemaName() {
        return schemaName;
    }


    public String getRemarks() {
        return remarks;
    }

    public List<TableMetadata> getTables() {
        return tables;
    }
}
