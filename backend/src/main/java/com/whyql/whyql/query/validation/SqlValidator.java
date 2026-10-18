package com.whyql.whyql.query.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.VisibilityRules;

/**
 * Hard gate for generated SQL using JSqlParser. Rejects anything that isn't a
 * single, read-only SELECT statement against allowlisted tables and columns.
 */
@Component
public class SqlValidator {

  private static final Set<String> DANGEROUS_FUNCTIONS = new HashSet<>(List.of(
      "pg_sleep", "pg_read_file", "pg_write_file", "pg_ls_dir",
      "copy", "load_file", "into_outfile", "into_dumpfile",
      "xp_cmdshell", "openrowset", "opendatasource"));

  public record ValidationResult(
      boolean valid,
      List<String> errors) {}

  /**
   * Validate a QueryPlan's SQL against the schema and visibility rules.
   */
  public ValidationResult validate(String sql, SchemaSnapshot snapshot, VisibilityRules rules) {
    List<String> errors = new ArrayList<>();

    if (sql == null || sql.trim().isEmpty()) {
      errors.add("Query text is empty");
      return new ValidationResult(false, errors);
    }

    net.sf.jsqlparser.statement.Statement stmt;
    try {
      stmt = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      errors.add("SQL parse error: " + e.getMessage());
      return new ValidationResult(false, errors);
    }

    // Must be a single SELECT statement
    if (!(stmt instanceof net.sf.jsqlparser.statement.select.Select)) {
      errors.add("Only SELECT statements are allowed");
      return new ValidationResult(false, errors);
    }

    net.sf.jsqlparser.statement.select.Select select =
        (net.sf.jsqlparser.statement.select.Select) stmt;

    // Get the PlainSelect body for table/column/LIMIT inspection
    net.sf.jsqlparser.statement.select.PlainSelect ps = select.getPlainSelect();
    if (ps == null) {
      errors.add("SELECT has no plain select body");
      return new ValidationResult(false, errors);
    }

    // Check for LIMIT enforcement
    if (ps.getLimit() == null) {
      errors.add("SELECT must enforce a LIMIT clause");
    }

    // Collect all tables referenced
    Set<String> referencedTables = collectTables(ps);

    // Validate tables against schema
    if (snapshot != null && snapshot.tables() != null) {
      Set<String> knownTables = new HashSet<>();
      for (var t : snapshot.tables()) {
        knownTables.add(t.name().toLowerCase(Locale.ROOT));
      }
      for (String t : referencedTables) {
        if (!knownTables.contains(t.toLowerCase(Locale.ROOT))) {
          errors.add("Table not in schema: " + t);
        }
      }
    }

    // Collect and validate columns against schema and visibility rules
    Set<String> referencedColumns = collectColumns(ps);
    if (snapshot != null && snapshot.tables() != null) {
      Set<String> knownColumns = new HashSet<>();
      Set<String> hiddenColumns = new HashSet<>();
      for (var t : snapshot.tables()) {
        for (var c : t.columns()) {
          knownColumns.add(c.name().toLowerCase(Locale.ROOT));
          if (c.hidden()) {
            hiddenColumns.add(c.name().toLowerCase(Locale.ROOT));
          }
        }
      }
      for (String c : referencedColumns) {
        String lc = c.toLowerCase(Locale.ROOT);
        if (!knownColumns.contains(lc)) {
          errors.add("Column not in schema: " + c);
        }
        if (hiddenColumns.contains(lc)) {
          errors.add("Hidden column referenced: " + c);
        }
      }
    }

    // Check for dangerous functions
    checkForDangerousFunctions(ps, errors);

    // Check for cross joins without conditions
    checkForCrossJoins(ps, errors);

    return new ValidationResult(errors.isEmpty(), errors);
  }

  private Set<String> collectTables(net.sf.jsqlparser.statement.select.PlainSelect ps) {
    Set<String> tables = new HashSet<>();
    if (ps == null) return tables;

    net.sf.jsqlparser.schema.Table from = ps.getFromItem(
        net.sf.jsqlparser.schema.Table.class);
    if (from != null && from.getName() != null) {
      tables.add(from.getName());
    }

    var joins = ps.getJoins();
    if (joins != null) {
      for (var join : joins) {
        if (join != null && join.getRightItem() != null
            && join.getRightItem() instanceof net.sf.jsqlparser.schema.Table t) {
          tables.add(t.getName());
        }
      }
    }

    return tables;
  }

  private Set<String> collectColumns(net.sf.jsqlparser.statement.select.PlainSelect ps) {
    Set<String> columns = new HashSet<>();
    if (ps == null) return columns;

    for (var item : ps.getSelectItems()) {
      if (item != null) {
        extractColumnsFromExpression(item.getExpression(), columns);
      }
    }
    if (ps.getWhere() != null) {
      extractColumnsFromExpression(ps.getWhere(), columns);
    }
    var joins = ps.getJoins();
    if (joins != null) {
      for (var join : joins) {
        try {
          if (join != null && join.getOnExpression() != null) {
            extractColumnsFromExpression(join.getOnExpression(), columns);
          }
        } catch (Exception e) {
          // Skip joins that can't be introspected
        }
      }
    }

    return columns;
  }

  private void extractColumnsFromExpression(net.sf.jsqlparser.expression.Expression expr,
      Set<String> columns) {
    if (expr == null) return;
    if (expr instanceof net.sf.jsqlparser.schema.Column col) {
      columns.add(col.getColumnName());
    } else if (expr instanceof net.sf.jsqlparser.expression.BinaryExpression be) {
      extractColumnsFromExpression(be.getLeftExpression(), columns);
      extractColumnsFromExpression(be.getRightExpression(), columns);
    } else if (expr instanceof net.sf.jsqlparser.expression.Function func) {
      if (func.getParameters() != null) {
        for (var arg : func.getParameters().getExpressions()) {
          extractColumnsFromExpression(arg, columns);
        }
      }
    } else if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
      extractColumnsFromExpression(p.getExpression(), columns);
    }
    // Literals, placeholders, and other expression types are ignored for column extraction
  }

  private void checkForDangerousFunctions(
      net.sf.jsqlparser.statement.select.PlainSelect ps, List<String> errors) {
    if (ps == null) return;
    checkSelectItemsForFunctions(ps.getSelectItems(), errors);
    if (ps.getWhere() != null) {
      checkExpressionForFunctions(ps.getWhere(), errors);
    }
    var joins = ps.getJoins();
    if (joins != null) {
      for (var join : joins) {
        try {
          if (join != null && join.getOnExpression() != null) {
            checkExpressionForFunctions(join.getOnExpression(), errors);
          }
        } catch (Exception e) {
          // Skip joins that can't be introspected
        }
      }
    }
  }

  private void checkSelectItemsForFunctions(
      List<net.sf.jsqlparser.statement.select.SelectItem<?>> items, List<String> errors) {
    if (items == null) return;
    for (var item : items) {
      if (item != null) {
        checkExpressionForFunctions(item.getExpression(), errors);
      }
    }
  }

  private void checkExpressionForFunctions(net.sf.jsqlparser.expression.Expression expr,
      List<String> errors) {
    if (expr == null) return;
    if (expr instanceof net.sf.jsqlparser.expression.Function func) {
      String name = func.getName() == null ? "" : func.getName().toString();
      if (DANGEROUS_FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
        errors.add("Dangerous function not allowed: " + name);
      }
      if (func.getParameters() != null) {
        for (var arg : func.getParameters().getExpressions()) {
          checkExpressionForFunctions(arg, errors);
        }
      }
    } else if (expr instanceof net.sf.jsqlparser.expression.BinaryExpression be) {
      checkExpressionForFunctions(be.getLeftExpression(), errors);
      checkExpressionForFunctions(be.getRightExpression(), errors);
    } else if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
      checkExpressionForFunctions(p.getExpression(), errors);
    }
  }

  private void checkForCrossJoins(
      net.sf.jsqlparser.statement.select.PlainSelect ps, List<String> errors) {
    if (ps == null) return;
    var joins = ps.getJoins();
    if (joins != null) {
      for (var join : joins) {
        try {
          if (join != null && join.isCross()) {
            if (join.getOnExpression() == null) {
              errors.add("Unconstrained cross join not allowed");
            }
          }
        } catch (Exception e) {
          // JSqlParser may not fully support CROSS JOIN introspection
          errors.add("Cross join detected");
        }
      }
    }
  }
}
