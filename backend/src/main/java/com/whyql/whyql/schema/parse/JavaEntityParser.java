package com.whyql.whyql.schema.parse;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;

/**
 * Parses JPA {@code @Entity} and Spring-Data {@code @Document} classes into tables
 * using JavaParser (no regexes over Java source). Reads {@code @Table(name)},
 * {@code @Column(name, nullable)}, {@code @Id}, {@code @Transient}, and
 * {@code @ManyToOne}/{@code @OneToOne} + {@code @JoinColumn} for foreign keys.
 * {@code @JoinTable} many-to-many helpers are skipped (documented, not silent: the
 * join table simply has no entity of its own in this mapping style).
 */
@Component
public class JavaEntityParser {

  public void parseSource(String javaSource, ParsedSchema schema) {
    final CompilationUnit unit;
    try {
      unit = StaticJavaParser.parse(javaSource);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "could not parse Java source: " + e.getMessage(), e);
    }
    for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
      if (type.isInterface() || !isEntity(type)) {
        continue;
      }
      ParsedSchema.TableBuilder table = schema.table(tableNameOf(type));
      for (FieldDeclaration field : type.getFields()) {
        if (field.isStatic() || isTransient(field)) {
          continue;
        }
        for (VariableDeclarator variable : field.getVariables()) {
          parseField(table, field, variable);
        }
      }
    }
  }

  private void parseField(ParsedSchema.TableBuilder table, FieldDeclaration field,
      VariableDeclarator variable) {
    String fieldName = variable.getNameAsString();
    String javaType = variable.getTypeAsString();
    String columnName = annotationValue(field, "Column", "name").orElse(fieldName);
    boolean nullable = annotationValue(field, "Column", "nullable")
        .map(value -> !value.equals("false")).orElse(true);
    boolean id = hasAnnotation(field, "Id") || hasAnnotation(field, "DocumentId");

    if (isRelation(field)) {
      // Relation fields become FK columns via @JoinColumn, defaulting to field_id.
      String joinColumn = annotationValue(field, "JoinColumn", "name").orElse(fieldName + "_id");
      String referencedColumn = annotationValue(field, "JoinColumn", "referencedColumnName")
          .orElse("id");
      table.column(joinColumn, "BIGINT", true);
      table.foreignKey(List.of(joinColumn), targetTable(field, javaType), List.of(referencedColumn));
      return;
    }

    table.column(columnName, javaToColumnType(javaType), nullable && !id);
    if (id) {
      table.primaryKey(columnName);
    }
  }

  private static boolean isEntity(ClassOrInterfaceDeclaration type) {
    return hasTypeAnnotation(type, "Entity") || hasTypeAnnotation(type, "Document");
  }

  private static boolean hasTypeAnnotation(ClassOrInterfaceDeclaration type, String name) {
    return type.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals(name));
  }

  private static String tableNameOf(ClassOrInterfaceDeclaration type) {
    Optional<String> explicit = type.getAnnotations().stream()
        .filter(a -> a.getNameAsString().equals("Table") || a.getNameAsString().equals("Document"))
        .findFirst()
        .flatMap(a -> annotationStringValue(a, "name").or(() -> annotationStringValue(a, "value"))
            .or(() -> annotationStringValue(a, "collection")));
    return explicit.filter(s -> !s.isBlank()).orElseGet(() -> toSnake(type.getNameAsString()));
  }

  private static boolean hasAnnotation(FieldDeclaration field, String name) {
    return field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals(name));
  }

  private static boolean isTransient(FieldDeclaration field) {
    return hasAnnotation(field, "Transient");
  }

  private static boolean isRelation(FieldDeclaration field) {
    return hasAnnotation(field, "ManyToOne") || hasAnnotation(field, "OneToOne");
  }

  private static String targetTable(FieldDeclaration field, String javaType) {
    // Generic collections (List<OrderItem>) and plain types both reduce to the raw name.
    String raw = javaType.contains("<")
        ? javaType.substring(javaType.indexOf('<') + 1, javaType.lastIndexOf('>'))
        : javaType;
    raw = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
    return toSnake(raw.replace("[]", ""));
  }

  private static Optional<String> annotationValue(FieldDeclaration field, String annotation,
      String member) {
    return field.getAnnotations().stream()
        .filter(a -> a.getNameAsString().equals(annotation))
        .findFirst()
        .flatMap(a -> annotationStringValue(a, member));
  }

  private static Optional<String> annotationStringValue(AnnotationExpr annotation, String member) {
    if (annotation instanceof SingleMemberAnnotationExpr single
        && (member.equals("value") || member.equals("name"))) {
      return Optional.of(stripQuotes(single.getMemberValue().toString()));
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        if (pair.getNameAsString().equals(member)) {
          return Optional.of(stripQuotes(pair.getValue().toString()));
        }
      }
    }
    return Optional.empty();
  }

  static String javaToColumnType(String javaType) {
    String base = javaType.contains("<") ? javaType.substring(0, javaType.indexOf('<')) : javaType;
    base = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
    return switch (base) {
      case "String" -> "TEXT";
      case "byte", "Byte", "short", "Short", "int", "Integer" -> "INTEGER";
      case "long", "Long" -> "BIGINT";
      case "float", "Float", "double", "Double" -> "DOUBLE PRECISION";
      case "BigDecimal" -> "NUMERIC";
      case "boolean", "Boolean" -> "BOOLEAN";
      case "LocalDate", "Date", "java.sql.Date" -> "DATE";
      case "LocalTime", "OffsetTime" -> "TIME";
      case "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Instant", "Timestamp" -> "TIMESTAMP";
      case "UUID" -> "UUID";
      case "byte[]" -> "BYTEA";
      default -> "TEXT"; // enums, embedded types and unknowns stay searchable text
    };
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

  private static String stripQuotes(String value) {
    String stripped = value.trim();
    if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
      stripped = stripped.substring(1, stripped.length() - 1);
    }
    return stripped;
  }
}
