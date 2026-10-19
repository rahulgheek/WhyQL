package com.whyql.whyql.schema.parse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;

/**
 * Liquibase XML changelogs (JDK DOM, no extra dependency): {@code createTable}
 * with {@code column} children, plus {@code addColumn} and
 * {@code addForeignKeyConstraint} applied in document order. YAML changelogs are
 * out of scope (documented): convert them to SQL or XML first.
 */
@Component
public class LiquibaseXmlParser {

  public void parseSource(String xml, ParsedSchema schema) {
    final Document document;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      document = factory.newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "could not parse Liquibase XML: " + e.getMessage(), e);
    }

    NodeList changeSets = document.getElementsByTagName("changeSet");
    for (int i = 0; i < changeSets.getLength(); i++) {
      Element changeSet = (Element) changeSets.item(i);
      NodeList children = changeSet.getChildNodes();
      for (int j = 0; j < children.getLength(); j++) {
        if (!(children.item(j) instanceof Element change)) {
          continue;
        }
        switch (change.getTagName()) {
          case "createTable" -> parseCreateTable(change, schema);
          case "addColumn" -> parseAddColumn(change, schema);
          case "addForeignKeyConstraint" -> parseForeignKey(change, schema);
          default -> { /* other changes do not affect the captured shape */ }
        }
      }
    }
  }

  private void parseCreateTable(Element change, ParsedSchema schema) {
    String tableName = change.getAttribute("tableName");
    if (tableName.isBlank()) {
      return;
    }
    ParsedSchema.TableBuilder table = schema.table(tableName);
    NodeList columns = change.getElementsByTagName("column");
    for (int i = 0; i < columns.getLength(); i++) {
      Element column = (Element) columns.item(i);
      String name = column.getAttribute("name");
      if (name.isBlank()) {
        continue;
      }
      String type = column.getAttribute("type");
      boolean nullable = !"false".equals(column.getAttribute("nullable"));
      // <constraints primaryKey="true" nullable="false" .../>
      NodeList constraints = column.getElementsByTagName("constraints");
      if (constraints.getLength() > 0) {
        Element constraint = (Element) constraints.item(0);
        if ("true".equals(constraint.getAttribute("primaryKey"))) {
          table.primaryKey(name);
          nullable = false;
        } else if ("false".equals(constraint.getAttribute("nullable"))) {
          nullable = false;
        }
      }
      table.column(name, type.isBlank() ? "UNKNOWN" : type.toUpperCase(), nullable);
    }
  }

  private void parseAddColumn(Element change, ParsedSchema schema) {
    String tableName = change.getAttribute("tableName");
    if (tableName.isBlank()) {
      return;
    }
    ParsedSchema.TableBuilder table = schema.table(tableName);
    NodeList columns = change.getElementsByTagName("column");
    for (int i = 0; i < columns.getLength(); i++) {
      Element column = (Element) columns.item(i);
      String name = column.getAttribute("name");
      if (name.isBlank()) {
        continue;
      }
      String type = column.getAttribute("type");
      table.column(name, type.isBlank() ? "UNKNOWN" : type.toUpperCase(), true);
    }
  }

  private void parseForeignKey(Element change, ParsedSchema schema) {
    String baseTable = change.getAttribute("baseTableName");
    String baseColumns = change.getAttribute("baseColumnNames");
    String referencedTable = change.getAttribute("referencedTableName");
    String referencedColumns = change.getAttribute("referencedColumnNames");
    if (baseTable.isBlank() || baseColumns.isBlank() || referencedTable.isBlank()) {
      return;
    }
    schema.table(baseTable).foreignKey(split(baseColumns), referencedTable,
        referencedColumns.isBlank() ? List.of("id") : split(referencedColumns));
  }

  private static List<String> split(String csv) {
    List<String> values = new ArrayList<>();
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return values;
  }
}
