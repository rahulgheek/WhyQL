package com.whyql.whyql.query.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.VisibilityRules;

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

    // Resolve the statement into leaf PlainSelects: a simple select is one
    // leaf; UNION/INTERSECT/EXCEPT compounds contribute one leaf per
    // component; parentheses are peeled. Anything unresolvable is rejected
    // (fail closed) — e.g. an unknown body shape would otherwise crash later
    // stages with a ClassCastException.
    List<net.sf.jsqlparser.statement.select.PlainSelect> leaves = resolveLeaves(select);
    if (leaves == null || leaves.isEmpty()) {
      errors.add(
          "Unsupported SELECT shape — only simple or UNION SELECT queries are allowed");
      return new ValidationResult(false, errors);
    }

    // LIMIT enforcement: on the statement itself (covers trailing LIMIT after
    // a compound select) or on at least one SELECT component.
    if (select.getLimit() == null && leaves.stream().allMatch(l -> l.getLimit() == null)) {
      errors.add("SELECT must enforce a LIMIT clause");
    }

    // Collect all tables referenced across every SELECT component
    Set<String> referencedTables = new HashSet<>();
    for (net.sf.jsqlparser.statement.select.PlainSelect leaf : leaves) {
      referencedTables.addAll(collectTables(leaf));
    }

    // CTE aliases are temporary names defined by the query itself; they are
    // not schema tables and must not fail the allowlist check. (Their bodies
    // are write-checked in checkForWriteShapes.)
    if (select.getWithItemsList() != null) {
      for (var item : select.getWithItemsList()) {
        if (item != null && item.getAliasName() != null) {
          referencedTables.remove(item.getAliasName().toLowerCase(Locale.ROOT));
        }
      }
    }

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
    Set<String> referencedColumns = new HashSet<>();
    for (net.sf.jsqlparser.statement.select.PlainSelect leaf : leaves) {
      referencedColumns.addAll(collectColumns(leaf));
    }
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

    // Check for dangerous functions in every component
    for (net.sf.jsqlparser.statement.select.PlainSelect leaf : leaves) {
      checkForDangerousFunctions(leaf, errors);
    }

    // The pipeline is strictly read-only: reject write-shaped SELECTs that a
    // plain "instanceof Select" check would let through.
    checkForWriteShapes(select, leaves, sql, errors);

    // Check for cross joins without conditions in every component
    for (net.sf.jsqlparser.statement.select.PlainSelect leaf : leaves) {
      checkForCrossJoins(leaf, errors);
    }

    return new ValidationResult(errors.isEmpty(), errors);
  }

  /**
   * Resolves a parsed SELECT into the set of leaf {@link PlainSelect}s it
   * contains. Returns {@code null} when any part cannot be resolved — callers
   * reject that shape (fail closed).
   */
  private List<net.sf.jsqlparser.statement.select.PlainSelect> resolveLeaves(
      net.sf.jsqlparser.statement.select.Select select) {
    return resolveLeaves(select, 0);
  }

  private List<net.sf.jsqlparser.statement.select.PlainSelect> resolveLeaves(
      net.sf.jsqlparser.statement.select.Select select, int depth) {
    // Depth guard against pathological nesting
    if (select == null || depth > 16) {
      return null;
    }
    if (select instanceof net.sf.jsqlparser.statement.select.PlainSelect ps) {
      return List.of(ps);
    }
    if (select instanceof net.sf.jsqlparser.statement.select.SetOperationList sop) {
      var components = sop.getSelects();
      if (components == null || components.isEmpty()) {
        return null;
      }
      List<net.sf.jsqlparser.statement.select.PlainSelect> leaves = new ArrayList<>();
      for (var component : components) {
        List<net.sf.jsqlparser.statement.select.PlainSelect> sub = resolveLeaves(component, depth + 1);
        if (sub == null) {
          return null;
        }
        leaves.addAll(sub);
      }
      return leaves;
    }
    if (select instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect paren) {
      try {
        net.sf.jsqlparser.statement.select.Select inner = paren.getSelect();
        if (inner == null || inner == select) {
          return null;
        }
        return resolveLeaves(inner, depth + 1);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
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

  /**
   * Text-level patterns that move or lock data while still parsing as a
   * SELECT. Kept as strings (not structural checks) because the shapes vary
   * per engine — false positives fail a query closed, which is the safe
   * direction. Also rejects CTEs whose body is a data-modifying statement
   * (PostgreSQL allows WITH x AS (DELETE ... RETURNING *) SELECT ...).
   */
  private static final Pattern WRITE_SHAPE = Pattern.compile(
      "\\bINTO\\s+(OUTFILE|DUMPFILE)\\b"      // MySQL: write server-side files
          + "|\\bINTO\\s+@"                   // MySQL: SELECT ... INTO @var
          + "|\\bFOR\\s+(UPDATE|SHARE)\\b");  // locking reads

  private void checkForWriteShapes(net.sf.jsqlparser.statement.select.Select select,
      List<net.sf.jsqlparser.statement.select.PlainSelect> leaves, String sql,
      List<String> errors) {
    if (leaves == null || leaves.isEmpty()) return;

    // PostgreSQL SELECT ... INTO tbl / SELECT ... INTO TEMP tbl creates tables.
    // Checked per component: `SELECT ... UNION SELECT ... INTO tbl` must not
    // slip through on the first component's empty into-tables.
    for (net.sf.jsqlparser.statement.select.PlainSelect ps : leaves) {
      if (ps.getIntoTempTable() != null
          || (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty())) {
        errors.add("SELECT ... INTO creates or writes tables and is not allowed");
      }
    }

    String upper = sql.toUpperCase(Locale.ROOT);
    if (WRITE_SHAPE.matcher(upper).find()) {
      errors.add("Write-shaped SELECT (INTO OUTFILE/DUMPFILE/@var or FOR UPDATE/SHARE) not allowed");
    }

    // CTE bodies must be plain selects: a data-modifying CTE executes a write
    // while the top-level statement still parses as a SELECT. Fail closed —
    // any body that cannot expose its wrapped SELECT is rejected.
    var withItems = select.getWithItemsList();
    if (withItems != null) {
      for (var item : withItems) {
        try {
          if (item == null || item.getSelect() == null
              || item.getSelect().getSelect() == null) {
            errors.add("CTE bodies may only contain SELECT queries");
          }
        } catch (Exception e) {
          errors.add("CTE bodies may only contain SELECT queries");
        }
      }
    }
  }
}
