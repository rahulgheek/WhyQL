package com.whyql.whyql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.whyql.whyql.auth.repository.OAuthAccountRepository;
import com.whyql.whyql.auth.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Auth REST API over HTTP (MockMvc, full context with real Postgres + Redis):
 * register/verify/login/refresh/logout/me plus the error-code contract and the
 * OAuth2 authorization redirect wiring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({AuthTestcontainersConfiguration.class, AuthControllerTest.TestConfig.class})
class AuthControllerTest {

  private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

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
  }

  @Autowired
  MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  UserRepository users;

  @Autowired
  OAuthAccountRepository oauthAccounts;

  @Autowired
  StringRedisTemplate redis;

  @Autowired
  JavaMailSender mailSender;

  @BeforeEach
  void reset() {
    oauthAccounts.deleteAll();
    users.deleteAll();
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    doNothing().when(mailSender).send(any(SimpleMailMessage.class));
  }

  private String lastOtp(ArgumentCaptor<SimpleMailMessage> captor) {
    verify(mailSender, atLeastOnce()).send(captor.capture());
    Matcher matcher = SIX_DIGITS.matcher(captor.getValue().getText());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  @Test
  void registerVerifyLoginMeRefreshLogoutOverHttp() throws Exception {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

    // Register -> 201 with PENDING user.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"password\":\"Str0ng!Pass\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("kate@example.com"))
        .andExpect(jsonPath("$.status").value("PENDING"));

    // Pending re-register → treated as a resend: 201 PENDING with a fresh OTP.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"password\":\"Str0ng!Pass\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("kate@example.com"))
        .andExpect(jsonPath("$.status").value("PENDING"));

    // Login before verification -> 403.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"password\":\"Str0ng!Pass\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_VERIFIED"));

    // Wrong OTP -> 400 INVALID_OTP.
    mockMvc.perform(post("/api/auth/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"code\":\"000000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_OTP"));

    // Real OTP (captured from the mocked outbound mail) -> 200 VERIFIED.
    String code = lastOtp(captor);
    mockMvc.perform(post("/api/auth/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"code\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VERIFIED"));

    // Registering a now-VERIFIED email → 409 USER_ALREADY_EXISTS.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"password\":\"Str0ng!Pass\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));

    // Login -> 200 with token pair.
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"password\":\"Str0ng!Pass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andReturn();
    JsonNode tokens = objectMapper.readTree(login.getResponse().getContentAsString());
    String accessToken = tokens.get("accessToken").asText();
    String refreshToken = tokens.get("refreshToken").asText();

    // /me with the Bearer token -> 200.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("kate@example.com"));

    // /me without a token -> 401.
    mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

    // /me with a garbage token -> 401 with the shared error code.
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

    // Refresh rotates the pair; the old refresh token dies.
    MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andReturn();
    String newRefreshToken =
        objectMapper.readTree(refreshed.getResponse().getContentAsString()).get("refreshToken").asText();

    mockMvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

    // Logout revokes the rotated token.
    mockMvc.perform(post("/api/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + newRefreshToken + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    mockMvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + newRefreshToken + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validationErrorsReturnStableCode() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(post("/api/auth/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"kate@example.com\",\"code\":\"12\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void oauthAuthorizationEndpointsRedirectToProviders() throws Exception {
    // No provider network call happens here: Spring only builds the redirect.
    MvcResult github = mockMvc.perform(get("/oauth2/authorization/github"))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    assertThat(github.getResponse().getRedirectedUrl()).contains("github.com");

    MvcResult google = mockMvc.perform(get("/oauth2/authorization/google"))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    assertThat(google.getResponse().getRedirectedUrl()).contains("accounts.google.com");
  }

  @Test
  void spaOAuthEntryStoresReturnCookieAndRedirectsToProvider() throws Exception {
    String returnTo = "http://localhost:3000/auth/callback";
    mockMvc.perform(get("/api/auth/oauth/github").queryParam("returnTo", returnTo))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/oauth2/authorization/github"))
        .andExpect(cookie().value("oauth_return", returnTo))
        .andExpect(cookie().httpOnly("oauth_return", true))
        .andExpect(cookie().maxAge("oauth_return", 300));

    // No returnTo -> straight hand-off, no return cookie is issued.
    mockMvc.perform(get("/api/auth/oauth/google"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/oauth2/authorization/google"))
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void spaOAuthEntryRejectsUnknownProviderAndUnlistedReturnTo() throws Exception {
    mockMvc.perform(get("/api/auth/oauth/facebook"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_UNSUPPORTED"));

    mockMvc.perform(get("/api/auth/oauth/github")
            .queryParam("returnTo", "http://evil.example.com/auth/callback"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("OAUTH_RETURN_INVALID"));
  }
}
