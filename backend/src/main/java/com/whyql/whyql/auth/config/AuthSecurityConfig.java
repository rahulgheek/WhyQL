package com.whyql.whyql.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.whyql.whyql.auth.security.OAuth2LoginFailureHandler;
import com.whyql.whyql.auth.security.OAuth2LoginSuccessHandler;
import com.whyql.whyql.shared.JwtAuthenticationFilter;

/**
 * Auth chain (order 1): public email/password endpoints, the Spring OAuth2 login
 * endpoints, and the authenticated GET /api/auth/me. Everything else falls through
 * to the shared chain.
 * Sessions stay at the Spring default (created when needed) because the OAuth2
 * authorization-code dance stores its state server-side; the API itself is stateless
 * JWT and never reads the session.
 */
@Configuration
public class AuthSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain authFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
      OAuth2LoginSuccessHandler successHandler, OAuth2LoginFailureHandler failureHandler) throws Exception {
    http.securityMatchers(matchers -> matchers
            .requestMatchers("/api/auth/**", "/oauth2/**", "/login/oauth2/**"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/verify-otp",
                "/api/auth/resend-otp", "/api/auth/login", "/api/auth/refresh",
                "/api/auth/logout")
            .permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/oauth/*")
            .permitAll()
            .requestMatchers("/oauth2/**", "/login/oauth2/**")
            .permitAll()
            .anyRequest()
            .authenticated())
        .oauth2Login(oauth -> oauth.successHandler(successHandler).failureHandler(failureHandler))
        .cors(Customizer.withDefaults())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
