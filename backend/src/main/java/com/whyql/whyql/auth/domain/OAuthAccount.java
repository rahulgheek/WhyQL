package com.whyql.whyql.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * A Google/GitHub account linked to a user. One provider account links to exactly
 * one user; a user may link both providers. Schema owned by Flyway V4.
 */
@Entity
@Table(name = "oauth_accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount {

  @Id
  @GeneratedValue
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 30)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Column
  private String email;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
