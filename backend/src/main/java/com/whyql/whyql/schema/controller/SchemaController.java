package com.whyql.whyql.schema.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;
import com.whyql.whyql.schema.dto.SchemaDtos;
import com.whyql.whyql.schema.dto.SchemaDtos.GitHubSyncRequest;
import com.whyql.whyql.schema.dto.SchemaDtos.ManualSchemaRequest;
import com.whyql.whyql.schema.dto.SchemaDtos.SnapshotResponse;
import com.whyql.whyql.schema.service.SchemaService;

/**
 * Schema ingestion from every origin: live introspection of a connected database,
 * SQL-file sandbox upload, model-code zips, GitHub repos, and manual definition.
 * Uploads are capped at 10 MB per file; zips are additionally bounded inside the
 * parser (entry count, entry size, total size).
 */
@RestController
@RequestMapping("/api/schemas")
public class SchemaController {

  private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

  private final SchemaService service;

  public SchemaController(SchemaService service) {
    this.service = service;
  }

  /** Live introspection of an owned data source → new snapshot version. */
  @PostMapping("/datasources/{dataSourceId}/sync")
  public ResponseEntity<SnapshotResponse> syncFromDataSource(@PathVariable UUID dataSourceId,
      Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toResponse(service.syncFromDataSource(dataSourceId, authentication.getName())));
  }

  /** Latest snapshot for a data source (404 SNAPSHOT_NOT_FOUND when never synced). */
  @GetMapping("/datasources/{dataSourceId}/latest")
  public SnapshotResponse latest(@PathVariable UUID dataSourceId, Authentication authentication) {
    return toResponse(service.latest(dataSourceId, authentication.getName()));
  }

  /** SQL file (DDL + optional seeds) → sandbox schema + queryable sandbox source. */
  @PostMapping(value = "/upload-sql", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<SnapshotResponse> uploadSql(@RequestParam String name,
      @RequestPart MultipartFile file, Authentication authentication) {
    String sql = readText(file);
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(
        service.uploadSql(authentication.getName(), name, sql)));
  }

  /** Zip of models/migrations → snapshot, optionally linked to a data source. */
  @PostMapping(value = "/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<SnapshotResponse> uploadZip(@RequestParam String name,
      @RequestParam(required = false) UUID dataSourceId,
      @RequestParam(required = false) String pathPrefix,
      @RequestPart MultipartFile file, Authentication authentication) {
    byte[] bytes = readBytes(file);
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.uploadZip(
        authentication.getName(), name, dataSourceId, pathPrefix, bytes)));
  }

  /** GitHub repo at ref (branch/tag/SHA, default branch when blank) → snapshot. */
  @PostMapping("/sync-github")
  public ResponseEntity<SnapshotResponse> syncGitHub(
      @Valid @RequestBody GitHubSyncRequest request, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.syncGitHub(
        authentication.getName(), request.name(), request.dataSourceId(), request.repoOwner(),
        request.repoName(), request.ref(), request.pathPrefix(), request.token())));
  }

  /** Manual table/column definition → snapshot (hidden flags honored). */
  @PostMapping("/manual")
  public ResponseEntity<SnapshotResponse> defineManual(
      @Valid @RequestBody ManualSchemaRequest request, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.defineManual(
        authentication.getName(), request.name(), request.dataSourceId(),
        SchemaDtos.toSnapshot(request))));
  }

  @GetMapping
  public List<SnapshotResponse> list(@RequestParam(required = false) UUID dataSourceId,
      Authentication authentication) {
    return service.list(authentication.getName(), dataSourceId).stream()
        .map(SchemaController::toResponse).toList();
  }

  @GetMapping("/{id}")
  public SnapshotResponse get(@PathVariable UUID id, Authentication authentication) {
    return toResponse(service.get(id, authentication.getName()));
  }

  private static SnapshotResponse toResponse(SchemaService.SnapshotView view) {
    return new SnapshotResponse(view.id(), view.dataSourceId(), view.name(), view.version(),
        view.source(), view.snapshot(), view.createdAt());
  }

  private static String readText(MultipartFile file) {
    byte[] bytes = readBytes(file);
    try {
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "file is not valid UTF-8 text", e);
    }
  }

  private static byte[] readBytes(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE, "file is empty");
    }
    if (file.getSize() > MAX_UPLOAD_BYTES) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "file exceeds 10 MB");
    }
    try {
      return file.getBytes();
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "could not read file: " + e.getMessage(), e);
    }
  }
}
