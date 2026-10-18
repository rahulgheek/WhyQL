package com.whyql.whyql.shared.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin access for the SPA (localhost:3000 in dev): the browser needs the
 * preflight answers before it will send Authorization-bearing fetches to
 * /api/auth/** and /graphql. Origins are configurable because the deployed
 * frontend lives on its own origin.
 */
@Configuration
public class CorsConfig {

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${whyql.cors.allowed-origins:http://localhost:3000}") String origins) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(origins.split(",")));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setMaxAge(600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
