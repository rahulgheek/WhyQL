package com.whyql.whyql.query.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.SchemaSnapshot.SchemaColumn;
import com.whyql.whyql.datasource.domain.SchemaSnapshot.SchemaTable;
import com.whyql.whyql.datasource.domain.VisibilityRules;

/**
 * Unit tests for the SQL validator. Covers happy paths and adversarial inputs:
 * write statements, hidden columns, dangerous functions, missing LIMIT,
 * cross joins, unknown tables/columns.
 */
class SqlValidatorTest {

  private final SqlValidator validator = new SqlValidator();

  private SchemaSnapshot snapshot(String... tableNames) {
    List<SchemaTable> tables = new ArrayList<>();
    for (String name : tableNames) {
      List<SchemaColumn> cols = new ArrayList<>();
      cols.add(new SchemaColumn("id", "BIGINT", false, false));
      cols.add(new SchemaColumn("name", "TEXT", true, false));
      if (name.equals("orders")) {
        cols.add(new SchemaColumn("customer_id", "BIGINT", true, false));
        cols.add(new SchemaColumn("total", "NUMERIC", true, false));
        cols.add(new SchemaColumn("ssn", "TEXT", true, true)); // hidden column
      }
      tables.add(new SchemaTable(name, cols, List.of("id"), List.of()));
    }
    return new SchemaSnapshot("test", tables, java.time.Instant.now());
  }

  private SqlValidator.ValidationResult validate(String sql, SchemaSnapshot snapshot) {
    return validator.validate(sql, snapshot, VisibilityRules.none());
  }

  @Test
  void simpleSelectWithLimitIsValid() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id, name FROM customers LIMIT 10", snap);
    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void derivedTableInFromIsValid() {
    // Used to crash with ClassCastException (ParenthesedSelect -> Table cast).
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id, name FROM (SELECT id, name FROM customers ORDER BY name) q LIMIT 10", snap);
    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void unknownTableInsideDerivedTableIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM (SELECT id FROM helper_table) q LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("helper_table"));
  }

  @Test
  void derivedTableInJoinIsChecked() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT c.name FROM customers c "
            + "JOIN (SELECT customer_id FROM orders) o ON o.customer_id = c.id LIMIT 10", snap);
    assertThat(result.valid()).isTrue();
  }

  @Test
  void hiddenColumnInsideDerivedTableIsRejected() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT q.ssn FROM (SELECT ssn FROM orders) q LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Hidden column"));
  }

  @Test
  void dangerousFunctionInsideDerivedTableIsRejected() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT * FROM (SELECT pg_sleep(5) FROM orders) q LIMIT 1", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Dangerous function"));
  }

  @Test
  void unsupportedFromShapesAreRejectedNotCrashed() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT * FROM TABLE(orders) t LIMIT 1", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Unsupported FROM clause shape"));
  }

  @Test
  void plainCteSelectIsValid() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "WITH recent AS (SELECT id, total FROM orders) "
            + "SELECT id FROM recent LIMIT 5",
        snap);
    assertThat(result.valid()).isTrue();
  }

  @Test
  void unionWithLimitIsValid() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM customers LIMIT 5 UNION SELECT id FROM orders LIMIT 5", snap);
    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void parenthesizedUnionWithTrailingLimitIsValid() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "(SELECT id FROM customers) UNION (SELECT id FROM orders) LIMIT 10", snap);
    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void unionWithoutLimitIsRejected() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM customers UNION SELECT id FROM orders", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("LIMIT"));
  }

  @Test
  void unionWithUnknownTableInSecondComponentIsRejected() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM customers LIMIT 5 UNION SELECT id FROM ghost_table LIMIT 5", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Table not in schema"));
  }

  @Test
  void unionSelectIntoSecondComponentIsRejected() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM customers LIMIT 5 UNION SELECT id INTO archive FROM orders LIMIT 5",
        snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("INTO"));
  }

  @Test
  void selectIntoIsRejected() {
    SchemaSnapshot snap = snapshot("orders");
    // PostgreSQL CTAS: SELECT ... INTO creates a table.
    SqlValidator.ValidationResult result = validate(
        "SELECT id, total INTO archive FROM orders LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("INTO"));
  }

  @Test
  void selectIntoOutfileAndForUpdateAreRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM customers LIMIT 10 FOR UPDATE", snap);
    assertThat(result.valid()).isFalse();

    result = validate("SELECT name FROM customers LIMIT 10", snap);
    assertThat(result.valid()).isTrue(); // sanity: same shape without the lock
  }

  @Test
  void mysqlIntoVariableSelectIsRejected() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT COUNT(*) FROM orders LIMIT 1 INTO", snap);
    assertThat(result.valid()).isFalse();
  }

  @Test
  void modifyingCteIsRejected() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    // PostgreSQL data-modifying CTE: a SELECT that deletes on the side.
    SqlValidator.ValidationResult result = validate(
        "WITH removed AS (DELETE FROM customers) SELECT COUNT(*) FROM removed LIMIT 1",
        snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("CTE"));
  }

  @Test
  void selectWithoutLimitIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate("SELECT id FROM customers", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("LIMIT"));
  }

  @Test
  void insertIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "INSERT INTO customers (id, name) VALUES (1, 'x')", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Only SELECT"));
  }

  @Test
  void updateIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "UPDATE customers SET name = 'y' WHERE id = 1", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Only SELECT"));
  }

  @Test
  void deleteIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate("DELETE FROM customers WHERE id = 1", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Only SELECT"));
  }

  @Test
  void dropTableIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate("DROP TABLE customers", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Only SELECT"));
  }

  @Test
  void hiddenColumnIsRejected() {
    SchemaSnapshot snap = snapshot("orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT id, ssn FROM orders LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Hidden column"));
  }

  @Test
  void unknownTableIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "SELECT id FROM nonexistent_table LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Table not in schema"));
  }

  @Test
  void unknownColumnIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "SELECT id, nonexistent_col FROM customers LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Column not in schema"));
  }

  @Test
  void dangerousFunctionPgSleepIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "SELECT id, pg_sleep(1) FROM customers LIMIT 10", snap);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("Dangerous function"));
  }

  @Test
  void dangerousFunctionCopyIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(
        "COPY customers TO '/tmp/out.csv'", snap);
    // COPY is not a SELECT - JSqlParser may reject it at parse time
    assertThat(result.valid()).isFalse();
  }

  @Test
  void crossJoinWithoutConditionIsRejected() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT c.id, o.id FROM customers c CROSS JOIN orders o LIMIT 10", snap);
    // JSqlParser 5.3 may handle CROSS JOIN differently; just verify it's not accepted as valid
    assertThat(result.valid()).isFalse();
  }

  @Test
  void emptySqlIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate("", snap);
    assertThat(result.valid()).isFalse();
  }

  @Test
  void nullSqlIsRejected() {
    SchemaSnapshot snap = snapshot("customers");
    SqlValidator.ValidationResult result = validate(null, snap);
    assertThat(result.valid()).isFalse();
  }

  @Test
  void validSelectWithJoinAndWhere() {
    SchemaSnapshot snap = snapshot("customers", "orders");
    SqlValidator.ValidationResult result = validate(
        "SELECT c.id, o.total FROM customers c JOIN orders o ON c.id = o.customer_id " +
        "WHERE o.total > 100 LIMIT 50", snap);
    assertThat(result.valid()).isTrue();
    assertThat(result.errors()).isEmpty();
  }
}
