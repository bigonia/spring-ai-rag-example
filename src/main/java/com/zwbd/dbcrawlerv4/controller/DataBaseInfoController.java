package com.zwbd.dbcrawlerv4.controller;

import com.zwbd.dbcrawlerv4.dto.database.DataBaseInfoDTO;
import com.zwbd.dbcrawlerv4.service.DataBaseInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for managing database information
 * Provides CRUD operations and connection testing endpoints
 */
@RestController
@RequestMapping("/api/database-info")
@AllArgsConstructor
@Tag(name = "Database Info", description = "Database information management APIs")
public class DataBaseInfoController {

    private static final Logger log = LoggerFactory.getLogger(DataBaseInfoController.class);
    private final DataBaseInfoService dataBaseInfoService;

    /**
     * Create a new database info record
     */
    @PostMapping
    @Operation(summary = "Create database info", description = "Create a new database information record")
    public ResponseEntity<DataBaseInfoDTO> create(@Valid @RequestBody DataBaseInfoDTO dto) {
        DataBaseInfoDTO created = dataBaseInfoService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update an existing database info record
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update database info", description = "Update an existing database information record")
    public ResponseEntity<DataBaseInfoDTO> update(
            @Parameter(description = "Database info ID") @PathVariable Long id,
            @Valid @RequestBody DataBaseInfoDTO dto) {
        DataBaseInfoDTO updated = dataBaseInfoService.update(id, dto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Get database info by ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get database info by ID", description = "Retrieve database information by ID")
    public ResponseEntity<DataBaseInfoDTO> getById(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        return dataBaseInfoService.findById(id)
                .map(dto -> ResponseEntity.ok(dto))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get database info by name
     */
    @GetMapping("/name/{name}")
    @Operation(summary = "Get database info by name", description = "Retrieve database information by name")
    public ResponseEntity<DataBaseInfoDTO> getByName(
            @Parameter(description = "Database info name") @PathVariable String name) {
        return dataBaseInfoService.findByName(name)
                .map(dto -> ResponseEntity.ok(dto))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all database info records
     */
    @GetMapping
    @Operation(summary = "Get all database info", description = "Retrieve all database information records")
    public ResponseEntity<List<DataBaseInfoDTO>> getAll() {
        List<DataBaseInfoDTO> dtos = dataBaseInfoService.findAll();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get database info records with pagination
     */
    @GetMapping("/page")
    @Operation(summary = "Get database info with pagination", description = "Retrieve database information records with pagination")
    public ResponseEntity<Page<DataBaseInfoDTO>> getAllPaged(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DataBaseInfoDTO> page = dataBaseInfoService.findAll(pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * Delete database info by ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete database info", description = "Delete database information record by ID")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        dataBaseInfoService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Test database connection by ID
     */
    @PostMapping("/{id}/test-connection")
    @Operation(summary = "Test database connection", description = "Test database connection by ID")
    public ResponseEntity<Map<String, Object>> testConnection(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        try {
            boolean isConnected = dataBaseInfoService.testConnection(id);
            return ResponseEntity.ok(Map.of(
                    "success", isConnected,
                    "message", isConnected ? "Connection successful" : "Connection failed"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Connection failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Test database connection using DTO (without saving)
     */
    @PostMapping("/test-connection")
    @Operation(summary = "Test database connection", description = "Test database connection using provided configuration")
    public ResponseEntity<Map<String, Object>> testConnection(@Valid @RequestBody DataBaseInfoDTO dto) {
        try {
            boolean isConnected = dataBaseInfoService.testConnection(dto);
            return ResponseEntity.ok(Map.of(
                    "success", isConnected,
                    "message", isConnected ? "Connection successful" : "Connection failed"
            ));
        } catch (Exception e) {
            log.error("testConnection", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Connection failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Check if database info exists by ID
     */
    @GetMapping("/{id}/exists")
    @Operation(summary = "Check if database info exists", description = "Check if database information exists by ID")
    public ResponseEntity<Map<String, Boolean>> existsById(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        boolean exists = dataBaseInfoService.existsById(id);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    /**
     * Check if database info exists by name
     */
    @GetMapping("/name/{name}/exists")
    @Operation(summary = "Check if database info exists by name", description = "Check if database information exists by name")
    public ResponseEntity<Map<String, Boolean>> existsByName(
            @Parameter(description = "Database info name") @PathVariable String name) {
        boolean exists = dataBaseInfoService.existsByName(name);
        return ResponseEntity.ok(Map.of("exists", exists));
    }
}