package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.service.DataSourceException;
import com.whyql.whyql.schema.parse.ParsedSchema;
import com.whyql.whyql.schema.parse.SqlSchemaParser;

/**
 * Plain unit test for DDL parsing: inline + table-level keys, seeds ignored,
 * garbage rejected. No Spring, no database.
 */
class SqlSchemaParserTest {

  private final SqlSchemaParser parser = new SqlSchemaParser();

  private static final String SHOP_DDL = """
      CREATE TABLE customers (
        id BIGINT PRIMARY KEY,
        email VARCHAR(255) NOT NULL,
        name TEXT,
        vip BOOLEAN DEFAULT false
      );
      CREATE TABLE orders (
        id BIGINT NOT NULL,
        customer_id BIGINT NOT NULL REFERENCES customers(id),
        total NUMERIC(10, 2),
        placed_at TIMESTAMP,
        PRIMARY KEY (id),
        CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
      );
      INSERT INTO customers VALUES (1, 'a@example.com', 'Ann', true);
      CREATE INDEX idx_orders_customer ON orders(customer_id);
      """;

  @Test
  void parsesTablesColumnsKeysAndForeignKeys() {
    ParsedSchema schema = parser.parse(SHOP_DDL);
    SchemaSnapshot snapshot = schema.toSnapshot("shop");

    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customers", "orders");

    SchemaSnapshot.SchemaTable customers = table(snapshot, "customers");
    assertThat(customers.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .containsExactly("id", "email", "name", "vip");
    assertThat(customers.primaryKeys()).containsExactly("id");
    assertThat(column(customers, "email").nullable()).isFalse();
    assertThat(column(customers, "name").nullable()).isTrue();

    SchemaSnapshot.SchemaTable orders = table(snapshot, "orders");
    assertThat(orders.foreignKeys()).hasSize(2); // inline REFERENCES + table constraint
    assertThat(orders.foreignKeys())
        .allMatch(fk -> fk.columns().equals(java.util.List.of("customer_id"))
            && fk.referencedTable().equals("customers"));
  }

  @Test
  void invalidSqlAndTablelessScriptsRejected() {
    assertThatThrownBy(() -> parser.parse("CREATE TABLE broken ("))
        .isInstanceOf(DataSourceException.class);
    assertThatThrownBy(() -> parser.parse("INSERT INTO t VALUES (1);"))
        .isInstanceOf(DataSourceException.class);
    assertThatThrownBy(() -> parser.parse("  "))
        .isInstanceOf(DataSourceException.class);
  }

  private static SchemaSnapshot.SchemaTable table(SchemaSnapshot snapshot, String name) {
    return snapshot.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static SchemaSnapshot.SchemaColumn column(SchemaSnapshot.SchemaTable table, String name) {
    return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }
}
