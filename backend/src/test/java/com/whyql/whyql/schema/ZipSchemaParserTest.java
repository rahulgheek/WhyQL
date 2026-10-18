package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.schema.parse.JavaEntityParser;
import com.whyql.whyql.schema.parse.LiquibaseXmlParser;
import com.whyql.whyql.schema.parse.PrismaSchemaParser;
import com.whyql.whyql.schema.parse.SqlSchemaParser;
import com.whyql.whyql.schema.parse.ZipSchemaParser;

/**
 * Plain unit test: an in-memory zip mixing .sql, entity .java, .prisma and
 * Liquibase XML merges into one schema; path prefixes scope monorepos; junk zips
 * fail with clear codes.
 */
class ZipSchemaParserTest {

  private final ZipSchemaParser parser = new ZipSchemaParser(new SqlSchemaParser(),
      new JavaEntityParser(), new PrismaSchemaParser(), new LiquibaseXmlParser());

  private static byte[] zip(String... nameAndContent) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        for (int i = 0; i < nameAndContent.length; i += 2) {
          zip.putNextEntry(new ZipEntry(nameAndContent[i]));
          zip.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
          zip.closeEntry();
        }
      }
      return bytes.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void mixedZipMergesIntoOneSchema() {
    byte[] bytes = zip(
        "db/migration/V1.sql", "CREATE TABLE customers (id BIGINT PRIMARY KEY, email TEXT NOT NULL);",
        "src/main/java/Customer.java",
        "import jakarta.persistence.*; @Entity public class Customer { @Id private Long id; }",
        "prisma/schema.prisma", "model Product { id Int @id\n sku String }",
        "README.md", "# not a schema file",
        "db/changelog.xml",
        "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\">"
            + "<changeSet id=\"1\" author=\"t\"><createTable tableName=\"orders\">"
            + "<column name=\"id\" type=\"BIGINT\"><constraints primaryKey=\"true\"/></column>"
            + "</createTable></changeSet></databaseChangeLog>");

    ZipSchemaParser.ZipParseResult result = parser.parse(bytes, null);

    assertThat(result.files()).hasSize(4);
    assertThat(result.schema().toSnapshot("x").tables())
        .extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customers", "customer", "product", "orders");
  }

  @Test
  void pathPrefixScopesMonorepos() {
    byte[] bytes = zip(
        "backend/schema.sql", "CREATE TABLE a (id INT PRIMARY KEY);",
        "frontend/schema.sql", "CREATE TABLE b (id INT PRIMARY KEY);");

    ZipSchemaParser.ZipParseResult result = parser.parse(bytes, "backend/");

    assertThat(result.files()).containsExactly("backend/schema.sql");
    assertThat(result.schema().toSnapshot("x").tables())
        .extracting(SchemaSnapshot.SchemaTable::name).containsExactly("a");
  }

  @Test
  void emptyAndJunkZipsRejected() {
    assertThatThrownBy(() -> parser.parse(new byte[0], null))
        .isInstanceOf(DataSourceException.class);
    assertThatThrownBy(() -> parser.parse(zip("README.md", "# nothing"), null))
        .isInstanceOf(DataSourceException.class);
    assertThatThrownBy(() -> parser.parse("not a zip".getBytes(StandardCharsets.UTF_8), null))
        .isInstanceOf(DataSourceException.class);
  }
}
