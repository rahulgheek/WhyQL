package com.whyql.whyql.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.whyql.whyql.shared.JwtAuthenticationFilter;

/**
 * Shared chain (catch-all after the auth chain): public health/GraphiQL endpoints,
 * everything else behind the Bearer JWT. Stateless API so CSRF is disabled and
 * unauthenticated API calls get a plain 401.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
      throws Exception {
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health", "/actuator/health", "/graphql", "/graphiql", "/graphiql/**")
            .permitAll()
            .anyRequest()
            .authenticated())
        .cors(Customizer.withDefaults())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
