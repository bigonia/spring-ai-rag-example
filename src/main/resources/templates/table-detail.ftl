# Table: ${schemaName}.${table.tableName()}

**Table Overview:**
- **Type**: ${table.tableType()}
<#if table.comment().isPresent()>
    - **Comment**: ${table.comment().get()}
</#if>
- **Row Count**: ${table.rowCount()}

---

**Column Details:**

| Column Name | Data Type | PK | Nullable | Comment & Metrics |
|---|---|---|---|---|
<#list columns as column>
    | ${column.columnName()?replace("|", "\\|")} | ${column.dataType()?replace("|", "\\|")} | <#if column.isPrimaryKey()>✅</#if> | <#if column.isNullable()>✅</#if> | ${column.commentAndMetrics()?replace("|", "\\|")} |
</#list>

<#-- 检查预处理过的 sampleData -->
<#--<#if table.sampleData().isPresent() && table.sampleData().get()?has_content>-->
<#--    ----->

<#--    **Data Sample:**-->

<#--    | <#list table.sampleData().get()[0]?keys as header>${header?replace("|", "\\|")}<#sep> | </#list> |-->
<#--    | <#list table.sampleData().get()[0]?keys as header>---<#sep>|</#list> |-->
<#--    <#list table.sampleData().get() as row>-->
<#--        | <#list row?values as value>&lt;#&ndash; 【已修正】因为 value 保证是字符串，不再需要 ?string 或 !"" &ndash;&gt;${value?replace("|", "\\|")}<#sep> | </#list> |-->
<#--    </#list>-->
<#--</#if>-->
