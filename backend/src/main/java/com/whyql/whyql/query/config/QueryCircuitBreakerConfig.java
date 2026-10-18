package com.whyql.whyql.query.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Programmatic Resilience4j circuit breaker for LLM calls (no
 * resilience4j-spring-boot3 starter; core modules only).
 *
 * NOTE: the class name must not collide with the imported simple names
 * (JLS 7.5.1 forbids a single-type import shadowing a same-named local type),
 * hence "QueryCircuitBreakerConfig" instead of "CircuitBreakerConfig".
 */
@Configuration
public class QueryCircuitBreakerConfig {

  @Bean
  public CircuitBreakerRegistry circuitBreakerRegistry() {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .slidingWindowSize(10)
        .minimumNumberOfCalls(5)
        .build();
    return CircuitBreakerRegistry.of(config);
  }

  @Bean
  public CircuitBreaker llmCircuitBreaker(CircuitBreakerRegistry registry) {
    return registry.circuitBreaker("llm");
  }
}
