package com.whyql.whyql.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.whyql.whyql.datasource.repository.DataSourceRepository;
import com.whyql.whyql.schema.repository.SchemaSnapshotRepository;
import com.whyql.whyql.shared.JwtService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Datasource REST over HTTP (full context, real Postgres + Redis): auth gating,
 * SSRF blocking, validation, and the sandbox lifecycle (upload SQL → source row →
 * test → delete).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({DatasourceTestcontainersConfiguration.class, DataSourceControllerTest.TestConfig.class})
class DataSourceControllerTest {

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    JavaMailSender mailSender() {
      return mock(JavaMailSender.class);
    }
  }

  @DynamicPropertySource
  static void testSecrets(DynamicPropertyRegistry registry) {
    registry.add("JWT_SECRET", () -> "test-secret-012345678901234567890123456789012345678901234");
    registry.add("ENCRYPTION_KEY", () -> "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=");
    // Pin the SSRF posture for this suite: a developer's backend/.env sets
    // WHYQL_CLOUD_MODE=false for local convenience, which would silently allow
    // 127.0.0.1 and turn the block test below into a wallpaper instead.
    registry.add("whyql.datasource.cloud-mode", () -> "true");
  }

  @Autowired
  MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  JwtService jwt;

  @Autowired
  DataSourceRepository dataSources;

  @Autowired
  SchemaSnapshotRepository snapshots;

  @Autowired
  JavaMailSender mailSender;

  private String bearer;

  @BeforeEach
  void reset() {
    snapshots.deleteAll();
    dataSources.deleteAll();
    doNothing().when(mailSender).send(any(SimpleMailMessage.class));
    bearer = "Bearer " + jwt.createAccessToken("user-1", "user-1@example.com");
  }

  @Test
  void unauthenticatedRequestsRejected() throws Exception {
    mockMvc.perform(get("/api/datasources")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/datasources").contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpect(status().isUnauthorized());
  }

  @Test
  void localhostUrlBlockedInCloudMode() throws Exception {
    mockMvc.perform(post("/api/datasources").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"local\",\"engine\":\"POSTGRES\","
                + "\"jdbcUrl\":\"jdbc:postgresql://127.0.0.1:5432/db\","
                + "\"username\":\"reader\",\"password\":\"s3cret\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("BLOCKED_HOST"));

    // Other users' data stays invisible (empty list, no leak).
    mockMvc.perform(get("/api/datasources").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void invalidSchemeRejectedWithStableCode() throws Exception {
    mockMvc.perform(post("/api/datasources").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"engine\":\"POSTGRES\",\"jdbcUrl\":\"http://example.com/db\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void sandboxLifecycleUploadTestDelete() throws Exception {
    String sql = "CREATE TABLE widgets (id BIGINT PRIMARY KEY, name TEXT);"
        + "INSERT INTO widgets VALUES (1, 'w');";
    MockMultipartFile file =
        new MockMultipartFile("file", "shop.sql", "application/sql", sql.getBytes(StandardCharsets.UTF_8));

    MvcResult uploaded = mockMvc
        .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
            "/api/schemas/upload-sql").file(file).param("name", "widgets").header("Authorization", bearer))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.snapshot.tables[0].name").value("widgets"))
        .andExpect(jsonPath("$.dataSourceId").isNotEmpty())
        .andReturn();
    JsonNode body = objectMapper.readTree(uploaded.getResponse().getContentAsString());
    String dataSourceId = body.get("dataSourceId").asText();

    // The sandbox source is listed and its stored connection tests green.
    mockMvc.perform(get("/api/datasources").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].sandbox").value(true));

    mockMvc.perform(post("/api/datasources/" + dataSourceId + "/test").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    // Latest snapshot + delete cascades to snapshots.
    mockMvc.perform(get("/api/schemas/datasources/" + dataSourceId + "/latest")
        .header("Authorization", bearer)).andExpect(status().isOk());

    mockMvc.perform(delete("/api/datasources/" + dataSourceId).header("Authorization", bearer))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/datasources/" + dataSourceId).header("Authorization", bearer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DATASOURCE_NOT_FOUND"));
    assertThat(snapshots.findByOwnerUserIdOrderByCreatedAtDesc("user-1")).isEmpty();
  }
}
