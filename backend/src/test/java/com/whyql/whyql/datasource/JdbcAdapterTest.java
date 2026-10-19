package com.whyql.whyql.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter.AdapterException;
import com.whyql.whyql.datasource.adapter.DataSourceAdapter.ConnectionParams;
import com.whyql.whyql.datasource.adapter.JdbcAdapter;
import com.whyql.whyql.datasource.adapter.profile.EngineProfiles;
import com.whyql.whyql.datasource.adapter.profile.MySqlProfile;
import com.whyql.whyql.datasource.adapter.profile.PostgresProfile;
import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.domain.ExecutionLimits;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.datasource.domain.VisibilityRules;

/**
 * JdbcAdapter against real Postgres and MySQL (Testcontainers, no Spring):
 * connection test, introspection (tables/columns/PK/FK), read-only execution
 * with row-cap truncation, write rejection, hidden-column stripping.
 */
@Testcontainers
class JdbcAdapterTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"));

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("whyql_test");

  private final JdbcAdapter adapter = new JdbcAdapter(new EngineProfiles(
      List.of(new PostgresProfile(), new MySqlProfile())));

  private static void createShopSchema(String jdbcUrl, String user, String password)
      throws Exception {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    try (Connection connection = DriverManager.getConnection(jdbcUrl, props);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS orders");
      statement.execute("DROP TABLE IF EXISTS customers");
      statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, "
          + "email VARCHAR(255) NOT NULL, name TEXT)");
      statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, "
          + "customer_id BIGINT NOT NULL, "
          + "total NUMERIC(10,2), placed_at TIMESTAMP, "
          + "FOREIGN KEY (customer_id) REFERENCES customers(id))");
      statement.execute("INSERT INTO customers VALUES (1, 'a@example.com', 'Ann')");
      statement.execute("INSERT INTO customers VALUES (2, 'b@example.com', 'Bob')");
      statement.execute("INSERT INTO orders VALUES (10, 1, 99.50, CURRENT_TIMESTAMP)");
      statement.execute("INSERT INTO orders VALUES (11, 1, 10.00, CURRENT_TIMESTAMP)");
      statement.execute("INSERT INTO orders VALUES (12, 2, 5.25, CURRENT_TIMESTAMP)");
    }
  }

  private static ConnectionParams pgParams() {
    return new ConnectionParams(Engine.POSTGRES, postgres.getJdbcUrl(), postgres.getUsername(),
        postgres.getPassword(), null);
  }

  @Test
  void postgresTestConnectionSucceedsAndFailsCleanly() throws Exception {
    createShopSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    adapter.testConnection(pgParams());

    ConnectionParams wrong = new ConnectionParams(Engine.POSTGRES, postgres.getJdbcUrl(),
        postgres.getUsername(), "wrong-password", null);
    assertThatThrownBy(() -> adapter.testConnection(wrong))
        .isInstanceOf(AdapterException.class)
        .satisfies(e -> {
          assertThat(((AdapterException) e).code()).isEqualTo("CONNECTION_FAILED");
          assertThat(e.getMessage()).doesNotContain("wrong-password");
        });
  }

  @Test
  void postgresIntrospectionFindsTablesColumnsKeys() throws Exception {
    createShopSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    SchemaSnapshot snapshot = adapter.introspect(pgParams());

    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .contains("customers", "orders");
    SchemaSnapshot.SchemaTable orders = snapshot.tables().stream()
        .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
    assertThat(orders.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .contains("id", "customer_id", "total", "placed_at");
    assertThat(orders.primaryKeys()).containsExactly("id");
    assertThat(orders.foreignKeys()).hasSize(1);
    assertThat(orders.foreignKeys().get(0).columns()).containsExactly("customer_id");
    assertThat(orders.foreignKeys().get(0).referencedTable()).isEqualTo("customers");
    assertThat(snapshot.tables()).isSortedAccordingTo(
        (a, b) -> a.name().compareToIgnoreCase(b.name()));
  }

  @Test
  void postgresExecuteSelectWithRowCapAndTruncation() throws Exception {
    createShopSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    QueryPlan plan = new QueryPlan(Engine.POSTGRES, "SELECT id, total FROM orders ORDER BY id",
        null, List.of(), List.of());

    TabularResult full = adapter.execute(plan, pgParams(), ExecutionLimits.defaults(),
        VisibilityRules.none());
    assertThat(full.rows()).hasSize(3);
    assertThat(full.truncated()).isFalse();
    assertThat(full.columns()).extracting(TabularResult.ResultColumn::name)
        .containsExactly("id", "total");

    TabularResult capped = adapter.execute(plan, pgParams(), new ExecutionLimits(30, 2),
        VisibilityRules.none());
    assertThat(capped.rows()).hasSize(2);
    assertThat(capped.truncated()).isTrue();
  }

  @Test
  void extensionTypeCellsComeBackAsPlainStrings() throws Exception {
    // citext (and jsonb) surface through getObject as PGobject wrappers;
    // without normalization they serialize as {"type":…,"value":…} and
    // clients render "[object Object]".
    Properties props = new Properties();
    props.setProperty("user", postgres.getUsername());
    props.setProperty("password", postgres.getPassword());
    try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), props);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS citext_probe");
      statement.execute("CREATE EXTENSION IF NOT EXISTS citext");
      statement.execute("CREATE TABLE citext_probe (id BIGINT PRIMARY KEY, email CITEXT)");
      statement.execute("INSERT INTO citext_probe VALUES (1, 'Kate@Example.com')");
    }

    QueryPlan plan = new QueryPlan(Engine.POSTGRES,
        "SELECT id, email FROM citext_probe ORDER BY id", null, List.of(), List.of());
    TabularResult result = adapter.execute(plan, pgParams(), ExecutionLimits.defaults(),
        VisibilityRules.none());

    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0).get(1)).isEqualTo("Kate@Example.com");
  }

  @Test
  void executeRejectsWritesAndStripsHiddenColumns() throws Exception {
    createShopSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    QueryPlan drop = new QueryPlan(Engine.POSTGRES, "DROP TABLE orders", null, List.of(), List.of());
    assertThatThrownBy(() -> adapter.execute(drop, pgParams(), ExecutionLimits.defaults(),
        VisibilityRules.none())).isInstanceOf(AdapterException.class)
        .satisfies(e -> assertThat(((AdapterException) e).code()).isEqualTo("NOT_READ_ONLY"));

    QueryPlan select = new QueryPlan(Engine.POSTGRES,
        "SELECT id, email, name FROM customers ORDER BY id", null, List.of(), List.of());
    VisibilityRules rules = new VisibilityRules(java.util.Set.of("customers.email"));
    TabularResult result = adapter.execute(select, pgParams(), ExecutionLimits.defaults(), rules);
    assertThat(result.columns()).extracting(TabularResult.ResultColumn::name)
        .containsExactly("id", "name");
  }

  @Test
  void validateRejectsForeignEngine() {
    QueryPlan plan = new QueryPlan(null, "SELECT 1", null, List.of(), List.of());
    assertThatThrownBy(
        () -> adapter.validate(plan, new SchemaSnapshot("s", List.of(), null), VisibilityRules.none()))
        .isInstanceOf(AdapterException.class);
  }

  @Test
  void mysqlIntrospectionWorks() throws Exception {
    createShopSchema(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    ConnectionParams params = new ConnectionParams(Engine.MYSQL, mysql.getJdbcUrl(),
        mysql.getUsername(), mysql.getPassword(), null);

    adapter.testConnection(params);
    SchemaSnapshot snapshot = adapter.introspect(params);

    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .contains("customers", "orders");
    SchemaSnapshot.SchemaTable customers = snapshot.tables().stream()
        .filter(t -> t.name().equals("customers")).findFirst().orElseThrow();
    assertThat(customers.primaryKeys()).containsExactly("id");
  }
}
