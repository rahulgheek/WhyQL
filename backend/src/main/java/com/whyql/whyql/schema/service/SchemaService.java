package com.whyql.whyql.schema.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.datasource.domain.DataSourceView;
import com.whyql.whyql.datasource.service.DatasourceGateway;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;
import com.whyql.whyql.integration.GitHubRepoFetcher;
import com.whyql.whyql.schema.domain.SchemaSnapshotRecord;
import com.whyql.whyql.schema.parse.ParsedSchema;
import com.whyql.whyql.schema.parse.SqlSchemaParser;
import com.whyql.whyql.schema.parse.ZipSchemaParser;
import com.whyql.whyql.schema.repository.SchemaSnapshotRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Schema ingestion from every supported origin, converging on stored snapshots:
 * live introspection, SQL-file sandbox upload, model-code zips, GitHub repos,
 * and manual definition. Versions increment per (owner, data source, name).
 */
@Service
public class SchemaService {

  private final SchemaSnapshotRepository repository;
  private final DatasourceGateway gateway;
  private final SandboxService sandboxService;
  private final SqlSchemaParser sqlParser;
  private final ZipSchemaParser zipParser;
  private final GitHubRepoFetcher gitHub;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SchemaService(SchemaSnapshotRepository repository, DatasourceGateway gateway,
      SandboxService sandboxService,
      SqlSchemaParser sqlParser, ZipSchemaParser zipParser, GitHubRepoFetcher gitHub) {
    this.repository = repository;
    this.gateway = gateway;
    this.sandboxService = sandboxService;
    this.sqlParser = sqlParser;
    this.zipParser = zipParser;
    this.gitHub = gitHub;
  }

  public record SnapshotView(UUID id, UUID dataSourceId, String name, int version, String source,
      SchemaSnapshot snapshot, String createdAt) {}

  /** Live introspection of an owned data source → new snapshot version. */
  @Transactional
  public SnapshotView syncFromDataSource(UUID dataSourceId, String ownerUserId) {
    DataSourceView view = gateway.get(dataSourceId, ownerUserId);
    SchemaSnapshot snapshot = gateway.introspect(dataSourceId, ownerUserId);
    if (snapshot.tables().isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.INTROSPECTION_FAILED,
          "no tables found in " + view.name());
    }
    return store(ownerUserId, dataSourceId, view.name(), snapshot, "introspection");
  }

  /**
   * SQL file upload: parsed for the snapshot AND loaded into an isolated sandbox
   * schema, creating a queryable sandbox data source in one step.
   */
  @Transactional
  public SnapshotView uploadSql(String ownerUserId, String name, String sql) {
    ParsedSchema parsed = sqlParser.parse(sql);
    String sandboxSchema = sandboxService.load(sql);
    DataSourceView view = gateway.createSandbox(ownerUserId, name, sandboxSchema);
    try {
      return store(ownerUserId, view.id(), name, parsed.toSnapshot(name), "sql-upload");
    } catch (RuntimeException e) {
      sandboxService.drop(sandboxSchema);
      throw e;
    }
  }

  /** Zip of models/migrations (.sql, JPA .java, .prisma, Liquibase XML) → snapshot. */
  @Transactional
  public SnapshotView uploadZip(String ownerUserId, String name, UUID dataSourceId,
      String pathPrefix, byte[] zipBytes) {
    if (dataSourceId != null) {
      gateway.get(dataSourceId, ownerUserId); // ownership check first
    }
    ZipSchemaParser.ZipParseResult result = zipParser.parse(zipBytes, pathPrefix);
    return store(ownerUserId, dataSourceId, name, result.schema().toSnapshot(name),
        "zip-upload (" + result.files().size() + " files)");
  }

  /** GitHub repo (zipball at ref, optional subfolder) → snapshot. */
  @Transactional
  public SnapshotView syncGitHub(String ownerUserId, String name, UUID dataSourceId,
      String repoOwner, String repoName, String ref, String pathPrefix, String token) {
    if (dataSourceId != null) {
      gateway.get(dataSourceId, ownerUserId); // ownership check first
    }
    final byte[] zip;
    try {
      zip = gitHub.downloadZip(repoOwner, repoName, ref, token);
    } catch (IllegalArgumentException e) {
      throw new DataSourceException(DataSourceErrorCode.GITHUB_FETCH_FAILED, e.getMessage(), e);
    } catch (GitHubRepoFetcher.GitHubFetchException e) {
      throw new DataSourceException(DataSourceErrorCode.GITHUB_FETCH_FAILED, e.getMessage(), e);
    }
    ZipSchemaParser.ZipParseResult result = zipParser.parse(zip, pathPrefix);
    return store(ownerUserId, dataSourceId, name,
        result.schema().toSnapshot(name),
        "github:" + repoOwner.trim() + "/" + repoName.trim() + " (" + result.files().size() + " files)");
  }

  /** Manual schema definition (table/column JSON) → snapshot. */
  @Transactional
  public SnapshotView defineManual(String ownerUserId, String name, UUID dataSourceId,
      SchemaSnapshot snapshot) {
    if (snapshot == null || snapshot.tables() == null || snapshot.tables().isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "manual schema needs at least one table");
    }
    validateNames(snapshot);
    if (dataSourceId != null) {
      gateway.get(dataSourceId, ownerUserId); // ownership check first
    }
    return store(ownerUserId, dataSourceId, name,
        new SchemaSnapshot(name, snapshot.tables(), java.time.Instant.now()), "manual");
  }

  @Transactional(readOnly = true)
  public SnapshotView latest(UUID dataSourceId, String ownerUserId) {
    gateway.get(dataSourceId, ownerUserId); // ownership check first
    List<SchemaSnapshotRecord> records =
        repository.findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(ownerUserId, dataSourceId);
    if (records.isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.SNAPSHOT_NOT_FOUND,
          "no schema snapshot yet; sync the data source first");
    }
    return toView(records.get(0));
  }

  @Transactional(readOnly = true)
  public List<SnapshotView> list(String ownerUserId, UUID dataSourceId) {
    List<SchemaSnapshotRecord> records;
    if (dataSourceId != null) {
      gateway.get(dataSourceId, ownerUserId);
      records = repository.findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(ownerUserId, dataSourceId);
    } else {
      records = repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
    }
    return records.stream().map(this::toView).toList();
  }

  @Transactional(readOnly = true)
  public SnapshotView get(UUID id, String ownerUserId) {
    return toView(repository.findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(() -> new DataSourceException(DataSourceErrorCode.SNAPSHOT_NOT_FOUND,
            "schema snapshot not found")));
  }

  private SnapshotView store(String ownerUserId, UUID dataSourceId, String name,
      SchemaSnapshot snapshot, String source) {
    int version = nextVersion(ownerUserId, dataSourceId, name);
    final String json;
    try {
      json = objectMapper.writeValueAsString(snapshot);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "could not serialize snapshot", e);
    }
    SchemaSnapshotRecord record = new SchemaSnapshotRecord();
    record.setOwnerUserId(ownerUserId);
    record.setDataSourceId(dataSourceId);
    record.setName(name);
    record.setVersion(version);
    record.setSnapshotJson(json);
    record.setSource(source);
    repository.save(record);
    return toView(record);
  }

  private int nextVersion(String ownerUserId, UUID dataSourceId, String name) {
    List<SchemaSnapshotRecord> existing;
    if (dataSourceId != null) {
      existing = repository.findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(ownerUserId, dataSourceId);
    } else {
      existing = repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
          .filter(r -> r.getDataSourceId() == null && r.getName().equals(name)).toList();
    }
    return existing.stream().mapToInt(SchemaSnapshotRecord::getVersion).max().orElse(0) + 1;
  }

  private SnapshotView toView(SchemaSnapshotRecord record) {
    final SchemaSnapshot snapshot;
    try {
      snapshot = objectMapper.readValue(record.getSnapshotJson(), SchemaSnapshot.class);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "stored snapshot is corrupt", e);
    }
    return new SnapshotView(record.getId(), record.getDataSourceId(), record.getName(),
        record.getVersion(), record.getSource(), snapshot, record.getCreatedAt().toString());
  }

  private static final java.util.regex.Pattern NAME_PATTERN =
      java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

  private static void validateNames(SchemaSnapshot snapshot) {
    for (SchemaSnapshot.SchemaTable table : snapshot.tables()) {
      requireName(table.name(), "table");
      for (SchemaSnapshot.SchemaColumn column : table.columns()) {
        requireName(column.name(), "column");
        if (column.type() == null || column.type().isBlank()) {
          throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
              "column " + column.name() + " needs a type");
        }
      }
    }
  }

  private static void requireName(String name, String what) {
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "invalid " + what + " name: " + name);
    }
  }

  /** Latest snapshot for query-time use; empty when never synced (callers decide). */
  @Transactional(readOnly = true)
  public Optional<SnapshotView> latestOrEmpty(UUID dataSourceId, String ownerUserId) {
    List<SchemaSnapshotRecord> records =
        repository.findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(ownerUserId, dataSourceId);
    return records.isEmpty() ? Optional.empty() : Optional.of(toView(records.get(0)));
  }
}
