package com.whyql.whyql.schema.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Minimal Prisma schema reader: {@code model} blocks with scalar fields and
 * {@code @relation(fields:[..], references:[..])} foreign keys. Only what schema
 * capture needs — not a full Prisma parser (datasource/generator blocks ignored).
 */
@Component
public class PrismaSchemaParser {

  private static final Pattern MODEL_START = Pattern.compile("^model\\s+(\\w+)\\s*\\{");
  private static final Pattern FIELD =
      Pattern.compile("^(\\w+)\\s+([\\w\\[\\]?]+)\\s*(.*)$");
  private static final Pattern MAP = Pattern.compile("@map\\(\"([^\"]+)\"\\)");
  private static final Pattern RELATION_FIELDS =
      Pattern.compile("@relation\\([^)]*fields\\s*:\\s*\\[([^\\]]*)\\][^)]*references\\s*:\\s*\\[([^\\]]*)\\]");

  public void parseSource(String prismaSource, ParsedSchema schema) {
    ParsedSchema.TableBuilder current = null;
    String currentModel = null;
    for (String rawLine : prismaSource.split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("//")) {
        continue;
      }
      Matcher modelMatcher = MODEL_START.matcher(line);
      if (modelMatcher.find()) {
        currentModel = modelMatcher.group(1);
        current = schema.table(toSnake(currentModel));
        continue;
      }
      if (line.equals("}")) {
        current = null;
        currentModel = null;
        continue;
      }
      if (current == null) {
        continue;
      }
      Matcher fieldMatcher = FIELD.matcher(line);
      if (!fieldMatcher.find()) {
        continue;
      }
      String fieldName = fieldMatcher.group(1);
      String fieldType = fieldMatcher.group(2);
      String attributes = fieldMatcher.group(3);

      if (attributes.contains("@ignore")) {
        continue;
      }
      Matcher relationMatcher = RELATION_FIELDS.matcher(attributes);
      if (relationMatcher.find()) {
        // Relation field: the scalar FK column is declared separately in Prisma.
        continue;
      }
      if (isRelationType(fieldType)) {
        continue; // object/list relation holder, no physical column
      }
      String columnName = mapName(attributes).orElse(toSnake(fieldName));
      boolean optional = fieldType.endsWith("?");
      String baseType = fieldType.replaceAll("[\\[\\]?]", "");
      current.column(columnName, prismaToColumnType(baseType), optional || !attributes.contains("@id"));
      if (attributes.contains("@id")) {
        // Single-column @id; composite @@id([a, b]) is handled in the second pass.
        current.primaryKey(columnName);
      }
    }
    // Composite @@id([a, b]) handled in a second pass (needs the full block).
    parseCompositeIds(prismaSource, schema);
    parseRelationScalars(prismaSource, schema);
  }

  private void parseCompositeIds(String source, ParsedSchema schema) {
    Pattern block = Pattern.compile("model\\s+(\\w+)\\s*\\{(.*?)\\}", Pattern.DOTALL);
    Matcher blockMatcher = block.matcher(source);
    while (blockMatcher.find()) {
      Matcher idMatcher = Pattern.compile("@@id\\(\\[([^\\]]*)\\]\\)").matcher(blockMatcher.group(2));
      if (idMatcher.find()) {
        ParsedSchema.TableBuilder table = schema.table(toSnake(blockMatcher.group(1)));
        for (String column : idMatcher.group(1).split(",")) {
          table.primaryKey(toSnake(column.trim()));
        }
      }
    }
  }

  private void parseRelationScalars(String source, ParsedSchema schema) {
    // Scalar FK columns already parsed as normal fields; link them via @relation lines.
    Pattern block = Pattern.compile("model\\s+(\\w+)\\s*\\{(.*?)\\}", Pattern.DOTALL);
    Matcher blockMatcher = block.matcher(source);
    while (blockMatcher.find()) {
      String model = toSnake(blockMatcher.group(1));
      ParsedSchema.TableBuilder table = schema.table(model);
      for (String rawLine : blockMatcher.group(2).split("\\R")) {
        Matcher relationMatcher = RELATION_FIELDS.matcher(rawLine.trim());
        if (relationMatcher.find()) {
          List<String> fields = splitList(relationMatcher.group(1));
          List<String> references = splitList(relationMatcher.group(2));
          String targetModel = targetModelOf(rawLine.trim());
          if (!fields.isEmpty() && targetModel != null) {
            table.foreignKey(fields.stream().map(PrismaSchemaParser::toSnake).toList(),
                toSnake(targetModel), references.stream().map(PrismaSchemaParser::toSnake).toList());
          }
        }
      }
    }
  }

  private static String targetModelOf(String line) {
    Matcher fieldMatcher = FIELD.matcher(line);
    if (fieldMatcher.find()) {
      String type = fieldMatcher.group(2).replaceAll("[\\[\\]?]", "");
      if (isRelationType(type) || Character.isUpperCase(type.charAt(0))) {
        return type;
      }
    }
    return null;
  }

  private static boolean isRelationType(String type) {
    String base = type.replaceAll("[\\[\\]?]", "");
    return switch (base) {
      case "String", "Int", "BigInt", "Float", "Decimal", "Boolean", "DateTime", "Json", "Bytes" -> false;
      default -> true;
    };
  }

  private static String prismaToColumnType(String base) {
    return switch (base) {
      case "String" -> "TEXT";
      case "Int" -> "INTEGER";
      case "BigInt" -> "BIGINT";
      case "Float" -> "DOUBLE PRECISION";
      case "Decimal" -> "NUMERIC";
      case "Boolean" -> "BOOLEAN";
      case "DateTime" -> "TIMESTAMP";
      case "Json" -> "JSON";
      case "Bytes" -> "BYTEA";
      default -> "TEXT";
    };
  }

  private static java.util.Optional<String> mapName(String attributes) {
    Matcher matcher = MAP.matcher(attributes);
    return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
  }

  private static List<String> splitList(String csv) {
    List<String> values = new ArrayList<>();
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return values;
  }

  static String toSnake(String name) {
    StringBuilder snake = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        snake.append('_');
      }
      snake.append(Character.toLowerCase(c));
    }
    return snake.toString().toLowerCase(Locale.ROOT);
  }
}
