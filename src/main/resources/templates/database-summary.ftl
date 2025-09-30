# Database Summary: ${dbMeta.databaseProductName()} (Version: ${dbMeta.databaseProductVersion()})

This document provides a high-level overview of the schemas and tables within the database.

<#list dbMeta.catalogs() as catalog>
    ## Schema: ${catalog.schemaName()}
    <#if catalog.remarks()?has_content>
        **Description**: ${catalog.remarks()}
    </#if>

    **Tables**:
    <#list catalog.tables() as table>
        - **${table.tableName()}**: ${table.comment().orElse("No comment available.")}
    </#list>

</#list>