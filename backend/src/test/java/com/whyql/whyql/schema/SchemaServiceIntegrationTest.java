package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.whyql.whyql.datasource.DatasourceGateway;
import com.whyql.whyql.datasource.DatasourceTestcontainersConfiguration;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.VisibilityRules;
import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.schema.dto.SchemaDtos;
import com.whyql.whyql.schema.repository.SchemaSnapshotRepository;
import com.whyql.whyql.schema.service.SchemaService;

/**
 * Schema ingestion end to end (real Postgres + Redis): SQL sandbox upload with
 * live re-introspection, zip upload, GitHub sync via a local stub, manual
 * definition with hidden flags, and delete-cascade cleanup.
 */
@SpringBootTest
@Import({DatasourceTestcontainersConfiguration.class, SchemaServiceIntegrationTest.TestConfig.class})
class SchemaServiceIntegrationTest {

  private static final String OWNER = "owner-1";

  private static HttpServer githubStub;
  private static String githubBase;

  static {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        zip.putNextEntry(new ZipEntry("shop/schema.sql"));
        zip.write(("CREATE TABLE planets (id BIGINT PRIMARY KEY, name TEXT NOT NULL);"
            + "INSERT INTO planets VALUES (1, 'Earth');").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      byte[] zipBytes = bytes.toByteArray();
      githubStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      githubStub.createContext("/repos/acme/shop", exchange -> {
        byte[] body = "{\"default_branch\":\"main\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      });
      githubStub.createContext("/repos/acme/shop/zipball/main", exchange -> {
        exchange.sendResponseHeaders(200, zipBytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(zipBytes);
        }
      });
      githubStub.start();
      githubBase = "http://127.0.0.1:" + githubStub.getAddress().getPort();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    JavaMailSender mailSender() {
      return mock(JavaMailSender.class);
    }
  }

  @DynamicPropertySource
  static void testSecrets(DynamicPropertyRegistry registry) {
    registry.add("JWT_SECRET", () -> "test-secret-012345678901234567890123456789012345678901234");
    registry.add("ENCRYPTION_KEY", () -> "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=");
    registry.add("whyql.github.api-url", () -> githubBase);
    registry.add("whyql.github.download-url", () -> githubBase);
  }

  @Autowired
  SchemaService schemas;

  @Autowired
  DatasourceGateway gateway;

  @Autowired
  SchemaSnapshotRepository repository;

  @Autowired
  StringRedisTemplate redis;

  private static final String SHOP_SQL = """
      CREATE TABLE customers (
        id BIGINT PRIMARY KEY,
        email VARCHAR(255) NOT NULL,
        name TEXT
      );
      CREATE TABLE orders (
        id BIGINT PRIMARY KEY,
        customer_id BIGINT NOT NULL REFERENCES customers(id),
        total NUMERIC(10,2)
      );
      INSERT INTO customers VALUES (1, 'a@example.com', 'Ann');
      INSERT INTO orders VALUES (10, 1, 99.50);
      """;

  @BeforeEach
  void reset() {
    repository.deleteAll();
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
  }

  @Test
  void uploadSqlCreatesSandboxSourceAndSnapshot() {
    SchemaService.SnapshotView view = schemas.uploadSql(OWNER, "shop", SHOP_SQL);

    assertThat(view.version()).isEqualTo(1);
    assertThat(view.source()).isEqualTo("sql-upload");
    assertThat(view.snapshot().tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customers", "orders");
    assertThat(view.dataSourceId()).isNotNull();
    assertThat(gateway.get(view.dataSourceId(), OWNER).sandbox()).isTrue();

    // Live re-introspection of the sandbox sees the same tables (seeds loaded too).
    SchemaService.SnapshotView synced = schemas.syncFromDataSource(view.dataSourceId(), OWNER);
    assertThat(synced.version()).isEqualTo(2);
    assertThat(synced.snapshot().tables()).hasSize(2);
  }

  @Test
  void uploadSqlRejectsNonSandboxStatements() {
    String mixed = "CREATE TABLE ok_table (id BIGINT PRIMARY KEY); DROP TABLE ok_table;";
    assertThatThrownBy(() -> schemas.uploadSql(OWNER, "evil", mixed))
        .isInstanceOf(DataSourceException.class)
        .satisfies(t -> assertThat(((DataSourceException) t).code())
            .isEqualTo(DataSourceErrorCode.INVALID_SCHEMA_FILE));
  }

  @Test
  void manualDefinitionHonorsHiddenFlags() {
    SchemaDtos.ManualSchemaRequest request = new SchemaDtos.ManualSchemaRequest("manual",
        null,
        List.of(new SchemaDtos.ManualTable("salaries",
            List.of(new SchemaDtos.ManualColumn("id", "BIGINT", false, false),
                new SchemaDtos.ManualColumn("amount", "NUMERIC", true, true)),
            List.of("id"), List.of())));

    SchemaService.SnapshotView view =
        schemas.defineManual(OWNER, "manual", null, SchemaDtos.toSnapshot(request));

    assertThat(view.version()).isEqualTo(1);
    VisibilityRules rules = VisibilityRules.fromSnapshot(view.snapshot());
    assertThat(rules.hiddenColumns()).containsExactly("salaries.amount");
  }

  @Test
  void githubSyncIngestsRepoZip() {
    SchemaService.SnapshotView view = schemas.syncGitHub(OWNER, "planets", null, "acme",
        "shop", null, null, null);

    assertThat(view.source()).startsWith("github:acme/shop");
    assertThat(view.snapshot().tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactly("planets");
  }

  @Test
  void deleteDataSourceCleansSnapshots() {
    SchemaService.SnapshotView view = schemas.uploadSql(OWNER, "shop", SHOP_SQL);
    UUID dataSourceId = view.dataSourceId();

    gateway.get(dataSourceId, OWNER); // exists
    // Delete through the service layer (event-driven cleanup runs after commit).
    deleteViaService(dataSourceId);

    assertThat(repository.findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(OWNER, dataSourceId))
        .isEmpty();
    assertThatThrownBy(() -> gateway.get(dataSourceId, OWNER)).isInstanceOf(DataSourceException.class)
        .satisfies(t -> assertThat(((DataSourceException) t).code())
            .isEqualTo(DataSourceErrorCode.DATASOURCE_NOT_FOUND));
  }

  @Autowired
  com.whyql.whyql.datasource.service.DataSourceService dataSourceService;

  private void deleteViaService(UUID dataSourceId) {
    dataSourceService.delete(dataSourceId, OWNER);
  }
}
