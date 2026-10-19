package com.whyql.whyql.schema.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;

/**
 * Walks an uploaded zip (or a GitHub tarball-turned-zip) and routes each file to
 * its parser by extension and path: *.sql (DDL/Flyway), *.java (JPA/@Document),
 * *.prisma (Prisma), *changelog*.xml / *liquibase*.xml (Liquibase). An optional
 * path prefix scopes the walk to one folder (monorepo support). Results merge
 * into one schema; later files refine (never erase) earlier columns.
 */
@Component
public class ZipSchemaParser {

  private static final long MAX_BYTES = 50L * 1024 * 1024;
  private static final int MAX_ENTRIES = 2000;

  private final SqlSchemaParser sqlParser;
  private final JavaEntityParser javaParser;
  private final PrismaSchemaParser prismaParser;
  private final LiquibaseXmlParser liquibaseParser;

  public ZipSchemaParser(SqlSchemaParser sqlParser, JavaEntityParser javaParser,
      PrismaSchemaParser prismaParser, LiquibaseXmlParser liquibaseParser) {
    this.sqlParser = sqlParser;
    this.javaParser = javaParser;
    this.prismaParser = prismaParser;
    this.liquibaseParser = liquibaseParser;
  }

  public record ZipParseResult(ParsedSchema schema, List<String> files) {}

  public ZipParseResult parse(byte[] zipBytes, String pathPrefix) {
    if (zipBytes == null || zipBytes.length == 0) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE, "zip is empty");
    }
    if (zipBytes.length > MAX_BYTES) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "zip exceeds 50 MB");
    }
    String prefix = pathPrefix == null ? "" : pathPrefix.trim().toLowerCase(Locale.ROOT);
    ParsedSchema schema = new ParsedSchema();
    List<String> files = new ArrayList<>();
    int entries = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
              "zip has too many entries (>" + MAX_ENTRIES + ")");
        }
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (!prefix.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          continue;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String content = readEntry(zip, lower);
        if (content == null) {
          continue;
        }
        if (lower.endsWith(".sql")) {
          merge(sqlParser.parse(content), schema);
          files.add(name);
        } else if (lower.endsWith(".java")) {
          javaParser.parseSource(content, schema);
          files.add(name);
        } else if (lower.endsWith(".prisma")) {
          prismaParser.parseSource(content, schema);
          files.add(name);
        } else if (lower.endsWith(".xml") && isLiquibasePath(lower)) {
          liquibaseParser.parseSource(content, schema);
          files.add(name);
        }
      }
    } catch (DataSourceException e) {
      throw e;
    } catch (IOException e) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "could not read zip: " + e.getMessage(), e);
    }
    if (schema.isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "no schema files found (.sql, JPA @Entity .java, .prisma, Liquibase XML)"
              + (prefix.isEmpty() ? "" : " under '" + pathPrefix + "'"));
    }
    return new ZipParseResult(schema, files);
  }

  private static boolean isLiquibasePath(String lower) {
    return lower.contains("changelog") || lower.contains("liquibase");
  }

  private static String readEntry(ZipInputStream zip, String name) throws IOException {
    // Zip-bomb guard: cap a single entry at 5 MB.
    byte[] buffer = new byte[8192];
    StringBuilder content = new StringBuilder();
    int total = 0;
    int read;
    while ((read = zip.read(buffer)) != -1) {
      total += read;
      if (total > 5 * 1024 * 1024) {
        throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
            "entry too large: " + name);
      }
      content.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
    }
    String text = content.toString();
    return text.isBlank() ? null : text;
  }

  private static void merge(ParsedSchema from, ParsedSchema into) {
    // SQL parse results merge table-by-table (ParsedSchema preserves insertion order).
    for (var table : from.toSnapshot(null).tables()) {
      ParsedSchema.TableBuilder target = into.table(table.name());
      for (var column : table.columns()) {
        target.column(column.name(), column.type(), column.nullable());
      }
      if (!table.primaryKeys().isEmpty()) {
        target.primaryKey(table.primaryKeys().toArray(String[]::new));
      }
      for (var fk : table.foreignKeys()) {
        target.foreignKey(fk.columns(), fk.referencedTable(), fk.referencedColumns());
      }
    }
  }
}
