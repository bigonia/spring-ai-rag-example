package com.zwbd.dbcrawlerv4.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized timeout configuration for database operations.
 * Provides configurable timeout values for different types of database operations.
 * 
 * @Author: wnli
 * @Date: 2025/9/19 16:55
 * @Desc: Timeout configuration management
 */
@Configuration
@ConfigurationProperties(prefix = "app.database.timeout")
public class TimeoutConfig {

    /**
     * Connection timeout in seconds for establishing database connections
     */
    private int connectionTimeout = 30;

    /**
     * Socket timeout in seconds for network operations
     */
    private int socketTimeout = 60;

    /**
     * Query timeout in seconds for connection test operations
     */
    private int connectionTestTimeout = 10;

    /**
     * Query timeout in seconds for table metadata queries
     */
    private int metadataQueryTimeout = 30;

    /**
     * Query timeout in seconds for metrics calculation queries
     */
    private int metricsCalculationTimeout = 120;

    /**
     * Overall task timeout in minutes for metadata collection tasks
     */
    private int taskTimeoutMinutes = 60;

    /**
     * Maximum concurrent tasks for parallel processing
     */
    private int maxConcurrentTasks = 5;

    // Getters and Setters
    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public int getConnectionTestTimeout() {
        return connectionTestTimeout;
    }

    public void setConnectionTestTimeout(int connectionTestTimeout) {
        this.connectionTestTimeout = connectionTestTimeout;
    }

    public int getMetadataQueryTimeout() {
        return metadataQueryTimeout;
    }

    public void setMetadataQueryTimeout(int metadataQueryTimeout) {
        this.metadataQueryTimeout = metadataQueryTimeout;
    }

    public int getMetricsCalculationTimeout() {
        return metricsCalculationTimeout;
    }

    public void setMetricsCalculationTimeout(int metricsCalculationTimeout) {
        this.metricsCalculationTimeout = metricsCalculationTimeout;
    }

    public int getTaskTimeoutMinutes() {
        return taskTimeoutMinutes;
    }

    public void setTaskTimeoutMinutes(int taskTimeoutMinutes) {
        this.taskTimeoutMinutes = taskTimeoutMinutes;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public void setMaxConcurrentTasks(int maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    /**
     * Get socket timeout in milliseconds for JDBC URL parameters
     */
    public int getSocketTimeoutMillis() {
        return socketTimeout * 1000;
    }

    /**
     * Get connection timeout in seconds
     */
    public int getConnectionTimeoutSeconds() {
        return connectionTimeout;
    }

    /**
     * Get general query timeout in seconds (for metadata queries)
     */
    public int getQueryTimeoutSeconds() {
        return metadataQueryTimeout;
    }

    /**
     * Get metrics calculation timeout in seconds
     */
    public int getMetricsQueryTimeoutSeconds() {
        return metricsCalculationTimeout;
    }

    @Override
    public String toString() {
        return "TimeoutConfig{" +
                "connectionTimeout=" + connectionTimeout +
                ", socketTimeout=" + socketTimeout +
                ", connectionTestTimeout=" + connectionTestTimeout +
                ", metadataQueryTimeout=" + metadataQueryTimeout +
                ", metricsCalculationTimeout=" + metricsCalculationTimeout +
                ", taskTimeoutMinutes=" + taskTimeoutMinutes +
                ", maxConcurrentTasks=" + maxConcurrentTasks +
                '}';
    }
}