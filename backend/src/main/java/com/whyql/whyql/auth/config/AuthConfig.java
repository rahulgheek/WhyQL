package com.whyql.whyql.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt for user passwords and OTP hashes: one well-documented algorithm, no extra
 * dependency (Argon2 would need Bouncy Castle). 10 rounds is the Spring default.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class AuthConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
