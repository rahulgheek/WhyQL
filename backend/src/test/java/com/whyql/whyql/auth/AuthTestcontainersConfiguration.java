package com.whyql.whyql.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test infra for auth tests: Postgres (pgvector image, same as the dev database)
 * plus Redis. No Kafka here, keeping the auth test context fast.
 */
@TestConfiguration(proxyBeanMethods = false)
class AuthTestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> pgvectorContainer() {
    return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"));
  }

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
  }
}
