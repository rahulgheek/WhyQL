package com.whyql.whyql.auth.dto;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request/response shapes for the auth REST API. One file on purpose: the whole
 * auth DTO surface fits on a screen and changes together.
 */
public final class AuthDtos {

  private AuthDtos() {}

  public record RegisterRequest(
      @NotBlank @Email String email,
      @NotBlank @Size(min = 8, max = 100) String password) {}

  public record VerifyOtpRequest(
      @NotBlank @Email String email,
      @NotBlank @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code) {}

  public record ResendOtpRequest(
      @NotBlank @Email String email) {}

  public record LoginRequest(
      @NotBlank @Email String email,
      @NotBlank String password) {}

  public record RefreshRequest(
      @NotBlank String refreshToken) {}

  public record LogoutRequest(
      @NotBlank String refreshToken) {}

  public record UserResponse(UUID id, String email, String status) {}

  public record TokenResponse(String accessToken, String refreshToken, int expiresInSeconds) {}

  public record AckResponse(boolean ok) {}
}
