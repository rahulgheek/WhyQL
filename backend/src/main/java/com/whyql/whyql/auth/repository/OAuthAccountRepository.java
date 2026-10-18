package com.whyql.whyql.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.auth.domain.OAuthAccount;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

  Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);
}
