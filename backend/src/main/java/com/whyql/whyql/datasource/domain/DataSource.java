package com.whyql.whyql.datasource.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * A connected database. Credentials are AES-GCM ciphertext (see CredentialCrypto);
 * plaintext only ever exists in memory inside this module. Sandbox rows point at an
 * isolated schema inside the app database instead of a URL.
 * Schema owned by Flyway V5; Hibernate only validates.
 */
@Entity
@Table(name = "data_sources")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSource {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false)
  private String ownerUserId;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Engine engine;

  /** AES-GCM Base64 ciphertext of the JDBC URL; null for sandbox sources. */
  @Column(columnDefinition = "TEXT")
  private String jdbcUrlEnc;

  /** AES-GCM Base64 ciphertext of the username; null for sandbox sources. */
  @Column(columnDefinition = "TEXT")
  private String usernameEnc;

  /** AES-GCM Base64 ciphertext of the password; null for sandbox sources. */
  @Column(columnDefinition = "TEXT")
  private String passwordEnc;

  /** Isolated app-DB schema for SQL-file uploads; null for URL sources. */
  @Column
  private String sandboxSchema;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
