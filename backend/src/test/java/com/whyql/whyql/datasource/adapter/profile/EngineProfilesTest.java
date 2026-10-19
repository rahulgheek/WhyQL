package com.whyql.whyql.datasource.adapter.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.domain.EngineDialects;

/**
 * Verifies the per-engine profile seam: registry mapping, system-namespace
 * rules, cross-namespace qualification and the dialect guidance both engines
 * must inject into the generation prompt.
 */
class EngineProfilesTest {

  private final EngineProfiles profiles = new EngineProfiles(
      List.of(new PostgresProfile(), new MySqlProfile()));

  @Test
  void bothJdbcEnginesHaveProfiles() throws DataSourceAdapter.AdapterException {
    assertThat(profiles.profile(Engine.POSTGRES)).isInstanceOf(PostgresProfile.class);
    assertThat(profiles.profile(Engine.MYSQL)).isInstanceOf(MySqlProfile.class);
  }

  @Test
  void unknownEngineIsRejectedAsEngineMismatch() {
    // Engine is a fixed enum (POSTGRES, MYSQL); a registry miss is what a
    // future engine without a profile looks like.
    DataSourceAdapter.AdapterException e =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> profiles.profile(null), DataSourceAdapter.AdapterException.class);
    assertThat(e).isNotNull();
    assertThat(e.code()).isEqualTo("ENGINE_MISMATCH");
    assertThat(e.getMessage()).contains("No SQL profile");
  }

  @Test
  void postgreSqlHidesSystemNamespaces() {
    PostgresProfile postgres = new PostgresProfile();
    assertThat(postgres.isSystemNamespace("pg_catalog")).isTrue();
    assertThat(postgres.isSystemNamespace("pg_toast")).isTrue();
    assertThat(postgres.isSystemNamespace("information_schema")).isTrue();
    assertThat(postgres.isSystemNamespace("public")).isFalse();
    assertThat(postgres.isSystemNamespace("finance")).isFalse();
    assertThat(postgres.isSystemNamespace(null)).isTrue();
  }

  @Test
  void mySqlHidesSystemDatabases() {
    MySqlProfile mysql = new MySqlProfile();
    assertThat(mysql.isSystemNamespace("mysql")).isTrue();
    assertThat(mysql.isSystemNamespace("performance_schema")).isTrue();
    assertThat(mysql.isSystemNamespace("sys")).isTrue();
    assertThat(mysql.isSystemNamespace("mydb")).isFalse();
  }

  @Test
  void postgreSqlQualifiesDuplicateTableNamesAcrossSchemas() {
    PostgresProfile postgres = new PostgresProfile();
    assertThat(postgres.duplicateDisplayName("sales", "customers"))
        .isEqualTo("sales.customers");
    assertThat(postgres.duplicateDisplayName("public", "customers"))
        .isEqualTo("public.customers");
  }

  @Test
  void mySqlCannotQualifyDuplicates() {
    assertThat(new MySqlProfile().duplicateDisplayName("mydb", "customers")).isNull();
  }

  @Test
  void bothDialectsGetPromptHintsIncludingLimitRule() {
    for (Engine engine : Engine.values()) {
      List<String> hints = EngineDialects.hints(engine);
      assertThat(hints).isNotEmpty();
      assertThat(EngineDialects.displayName(engine)).isNotBlank();
      assertThat(hints).anyMatch(h -> h.toLowerCase().contains("limit"));
    }
  }

  @Test
  void dialectHintsDifferPerEngine() {
    assertThat(EngineDialects.hints(Engine.POSTGRES))
        .isNotEqualTo(EngineDialects.hints(Engine.MYSQL));
    assertThat(EngineDialects.hints(Engine.POSTGRES))
        .anyMatch(h -> h.contains("ILIKE"));
    assertThat(EngineDialects.hints(Engine.MYSQL))
        .anyMatch(h -> h.contains("LOWER(col)"));
  }
}
