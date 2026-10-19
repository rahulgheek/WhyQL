package com.whyql.whyql.datasource.adapter.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * MySQL profile. MySQL has no schema layer: one connection = one database,
 * so the namespace is the connected catalog and tables come straight from
 * the information_schema for that database.
 */
@Component
public class MySqlProfile implements EngineProfile {

  @Override
  public Engine engine() {
    return Engine.MYSQL;
  }

  @Override
  public String displayName() {
    return "MySQL";
  }

  @Override
  public String driverClassName() {
    return "com.mysql.cj.jdbc.Driver";
  }

  @Override
  public boolean isSystemNamespace(String namespace) {
    return namespace == null
        || List.of("information_schema", "mysql", "performance_schema", "sys").contains(namespace);
  }

  @Override
  public void tune(Connection connection, String namespace) {
    // Nothing to pin: the database is part of the JDBC URL / catalog.
  }

  @Override
  public List<String> readableNamespaces(Connection connection) throws Exception {
    String catalog = connection.getCatalog();
    return catalog == null || catalog.isBlank() ? List.of() : List.of(catalog);
  }

  @Override
  public Set<String> readableTables(Connection connection, String namespace) throws Exception {
    String sql = "SELECT table_name FROM information_schema.tables "
        + "WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW')";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, namespace);
      try (ResultSet rs = ps.executeQuery()) {
        Set<String> tables = new LinkedHashSet<>();
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
        return tables;
      }
    }
  }

  @Override
  public String nothingReadable() {
    return "No tables visible in the MySQL database. Check the /database part of the "
        + "JDBC URL and that this user has grants on that database (inheritable MySQL "
        + "host wildcards sometimes block remote access).";
  }
}
