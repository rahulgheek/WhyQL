package com.whyql.whyql.datasource.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.whyql.whyql.datasource.domain.DataSourceView;
import com.whyql.whyql.datasource.dto.DataSourceDtos.AddDataSourceRequest;
import com.whyql.whyql.datasource.dto.DataSourceDtos.DataSourceResponse;
import com.whyql.whyql.datasource.dto.DataSourceDtos.RevealedCredentialsResponse;
import com.whyql.whyql.datasource.dto.DataSourceDtos.RevealRequest;
import com.whyql.whyql.datasource.dto.DataSourceDtos.TestConnectionResponse;
import com.whyql.whyql.datasource.dto.DataSourceDtos.UpdateCredentialsRequest;
import com.whyql.whyql.datasource.service.DataSourceService;

/**
 * URL data sources (Postgres/MySQL). The connection is tested before anything is
 * saved; stored credentials are AES-GCM ciphertext and never returned by any
 * endpoint. SQL-file, zip, GitHub and manual schema ingestion live in the schema
 * module's SchemaController.
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

  private final DataSourceService service;

  public DataSourceController(DataSourceService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<DataSourceResponse> add(@Valid @RequestBody AddDataSourceRequest request,
      Authentication authentication) {
    DataSourceService.AddResult result = service.addByUrl(authentication.getName(),
        request.name(), request.engine(), request.jdbcUrl(), request.username(),
        request.password());
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result.view(), result.warnings()));
  }

  /**
   * Rotates stored credentials (any subset of url/username/password; null
   * fields keep the stored value). The merged connection is tested live
   * before anything is saved — a typo never bricks the source.
   */
  @PutMapping("/{id}/credentials")
  public TestConnectionResponse updateCredentials(@PathVariable UUID id,
      @Valid @RequestBody UpdateCredentialsRequest request, Authentication authentication) {
    List<String> warnings = service.updateCredentials(id, authentication.getName(),
        request.jdbcUrl(), request.username(), request.password(),
        request.accountPassword()).warnings();
    return new TestConnectionResponse(true, warnings);
  }

  /**
   * Account-password-gated credential reveal, rate limited per user. This
   * is the ONLY endpoint that returns the stored plaintext.
   */
  @PostMapping("/{id}/credentials/reveal")
  public RevealedCredentialsResponse reveal(@PathVariable UUID id,
      @Valid @RequestBody RevealRequest request, Authentication authentication) {
    DataSourceService.RevealedCredentials revealed = service.revealedCredentials(
        id, authentication.getName(), request.accountPassword());
    return new RevealedCredentialsResponse(revealed.id(), revealed.jdbcUrl(),
        revealed.username(), revealed.password());
  }

  @GetMapping
  public List<DataSourceResponse> list(Authentication authentication) {
    return service.list(authentication.getName()).stream()
        .map(view -> toResponse(view, List.of())).toList();
  }

  @GetMapping("/{id}")
  public DataSourceResponse get(@PathVariable UUID id, Authentication authentication) {
    return toResponse(service.get(id, authentication.getName()), List.of());
  }

  @PostMapping("/{id}/test")
  public TestConnectionResponse test(@PathVariable UUID id, Authentication authentication) {
    List<String> warnings = service.testConnection(id, authentication.getName());
    return new TestConnectionResponse(true, warnings);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
    service.delete(id, authentication.getName());
    return ResponseEntity.noContent().build();
  }

  private static DataSourceResponse toResponse(DataSourceView view, List<String> warnings) {
    return new DataSourceResponse(view.id(), view.name(), view.engine(), view.sandbox(),
        view.createdAt().toString(), warnings);
  }
}
