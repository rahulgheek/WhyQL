package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.schema.parse.ParsedSchema;
import com.whyql.whyql.schema.parse.PrismaSchemaParser;

/**
 * Plain unit test for the minimal Prisma reader: models, scalars, @map, @id,
 * relations with scalar FK columns.
 */
class PrismaSchemaParserTest {

  private final PrismaSchemaParser parser = new PrismaSchemaParser();

  private static final String SCHEMA = """
      generator client {
        provider = "prisma-client-js"
      }
      datasource db {
        provider = "postgresql"
        url      = env("DATABASE_URL")
      }
      model Customer {
        id        Int      @id @default(autoincrement())
        email     String   @unique
        name      String?
        createdAt DateTime @default(now())
        orders    Order[]
      }
      model Order {
        id         Int      @id @default(autoincrement())
        customerId Int      @map("customer_id")
        total      Decimal
        customer   Customer @relation(fields: [customerId], references: [id])
      }
      """;

  @Test
  void modelsBecomeTablesWithRelations() {
    ParsedSchema schema = new ParsedSchema();
    parser.parseSource(SCHEMA, schema);

    SchemaSnapshot snapshot = schema.toSnapshot("shop");
    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customer", "order");

    SchemaSnapshot.SchemaTable customer = table(snapshot, "customer");
    assertThat(customer.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .containsExactly("id", "email", "name", "created_at");
    assertThat(customer.primaryKeys()).containsExactly("id");
    assertThat(column(customer, "email").type()).isEqualTo("TEXT");
    assertThat(column(customer, "created_at").type()).isEqualTo("TIMESTAMP");

    SchemaSnapshot.SchemaTable order = table(snapshot, "order");
    assertThat(order.foreignKeys()).hasSize(1);
    assertThat(order.foreignKeys().get(0).columns()).containsExactly("customer_id");
    assertThat(order.foreignKeys().get(0).referencedTable()).isEqualTo("customer");
    assertThat(order.foreignKeys().get(0).referencedColumns()).containsExactly("id");
    assertThat(column(order, "total").type()).isEqualTo("NUMERIC");
  }

  private static SchemaSnapshot.SchemaTable table(SchemaSnapshot snapshot, String name) {
    return snapshot.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static SchemaSnapshot.SchemaColumn column(SchemaSnapshot.SchemaTable table, String name) {
    return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }
}
