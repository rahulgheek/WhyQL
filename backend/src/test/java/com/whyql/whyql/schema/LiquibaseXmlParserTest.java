package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.schema.parse.LiquibaseXmlParser;
import com.whyql.whyql.schema.parse.ParsedSchema;

/**
 * Plain unit test for Liquibase XML changelogs: createTable, addColumn,
 * addForeignKeyConstraint applied in order.
 */
class LiquibaseXmlParserTest {

  private final LiquibaseXmlParser parser = new LiquibaseXmlParser();

  private static final String CHANGELOG = """
      <?xml version="1.0" encoding="UTF-8"?>
      <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
        <changeSet id="1" author="dev">
          <createTable tableName="customers">
            <column name="id" type="BIGINT">
              <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="email" type="VARCHAR(255)">
              <constraints nullable="false"/>
            </column>
          </createTable>
        </changeSet>
        <changeSet id="2" author="dev">
          <createTable tableName="orders">
            <column name="id" type="BIGINT">
              <constraints primaryKey="true"/>
            </column>
            <column name="customer_id" type="BIGINT"/>
          </createTable>
          <addForeignKeyConstraint baseTableName="orders" baseColumnNames="customer_id"
            referencedTableName="customers" referencedColumnNames="id"
            constraintName="fk_orders_customer"/>
        </changeSet>
        <changeSet id="3" author="dev">
          <addColumn tableName="customers">
            <column name="name" type="TEXT"/>
          </addColumn>
        </changeSet>
      </databaseChangeLog>
      """;

  @Test
  void changelogBuildsTablesIncrementally() {
    ParsedSchema schema = new ParsedSchema();
    parser.parseSource(CHANGELOG, schema);

    SchemaSnapshot snapshot = schema.toSnapshot("shop");
    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customers", "orders");

    SchemaSnapshot.SchemaTable customers = table(snapshot, "customers");
    assertThat(customers.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .containsExactly("id", "email", "name");
    assertThat(customers.primaryKeys()).containsExactly("id");
    assertThat(column(customers, "email").nullable()).isFalse();

    SchemaSnapshot.SchemaTable orders = table(snapshot, "orders");
    assertThat(orders.foreignKeys()).hasSize(1);
    assertThat(orders.foreignKeys().get(0).referencedTable()).isEqualTo("customers");
  }

  private static SchemaSnapshot.SchemaTable table(SchemaSnapshot snapshot, String name) {
    return snapshot.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static SchemaSnapshot.SchemaColumn column(SchemaSnapshot.SchemaTable table, String name) {
    return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }
}
