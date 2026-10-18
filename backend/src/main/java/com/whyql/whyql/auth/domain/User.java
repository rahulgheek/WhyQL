package com.whyql.whyql.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Registered user. passwordHash is null for OAuth-only accounts (created via
 * Google/GitHub); those users cannot log in with a password.
 * Schema is owned by Flyway (V3 users table, V4 nullable password + oauth_accounts);
 * Hibernate only validates.
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false)
  private String email;

  @Column
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserStatus status = UserStatus.PENDING;

  private Instant emailVerifiedAt;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  private Instant lastLoginAt;
}
