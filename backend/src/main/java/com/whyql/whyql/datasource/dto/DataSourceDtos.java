package com.whyql.whyql.datasource.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * Datasource REST shapes. Secrets only ever travel client → server on creation;
 * every response is secret-free (warnings reference usernames/hosts, never URLs).
 */
public final class DataSourceDtos {

  private DataSourceDtos() {}

  public record AddDataSourceRequest(
      @NotBlank String name,
      @NotNull Engine engine,
      @NotBlank @Pattern(regexp = "^jdbc:(postgresql|mysql)://.+",
          message = "only jdbc:postgresql:// and jdbc:mysql:// URLs are supported") String jdbcUrl,
      String username,
      String password) {}

  public record DataSourceResponse(
      UUID id,
      String name,
      Engine engine,
      boolean sandbox,
      String createdAt,
      List<String> warnings) {}

  public record TestConnectionResponse(boolean ok, List<String> warnings) {}

  /** Rotation/edit of stored credentials; null fields keep the stored value.
   * The caller re-proves identity with their account password (same gate as reveal). */
  public record UpdateCredentialsRequest(String jdbcUrl, String username, String password,
      @NotBlank String accountPassword) {}

  /** Reveal gate: requires the caller's WhyQL account password. */
  public record RevealRequest(@NotBlank String accountPassword) {}

  /** Plaintext credentials — only ever returned by the gated reveal. */
  public record RevealedCredentialsResponse(
      UUID id, String jdbcUrl, String username, String password) {}
}
