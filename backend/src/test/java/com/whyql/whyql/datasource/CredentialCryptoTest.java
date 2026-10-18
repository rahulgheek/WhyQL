package com.whyql.whyql.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.service.CredentialCrypto;

/**
 * Plain unit test: AES-GCM roundtrip, key validation, tamper detection.
 * No Spring context needed (constructor takes the raw env value).
 */
class CredentialCryptoTest {

  // Base64("0123456789ABCDEF0123456789ABCDEF") — 32 bytes, deterministic for tests.
  private static final String KEY = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";
  private static final String OTHER_KEY = "QUJDREVGMDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODkwMTI=";

  @Test
  void roundtripJdbcUrlAndPassword() {
    CredentialCrypto crypto = new CredentialCrypto(KEY);

    String url = "jdbc:postgresql://db.example.com:5432/analytics?sslmode=require";
    String password = "s3cr3t-p@ss w1th spaces!";
    assertThat(crypto.decrypt(crypto.encrypt(url))).isEqualTo(url);
    assertThat(crypto.decrypt(crypto.encrypt(password))).isEqualTo(password);
  }

  @Test
  void samePlaintextEncryptsDifferently() {
    CredentialCrypto crypto = new CredentialCrypto(KEY);
    // Fresh random IV per value: ciphertexts must differ (no ECB-style leakage).
    assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
  }

  @Test
  void ciphertextDoesNotContainPlaintext() {
    CredentialCrypto crypto = new CredentialCrypto(KEY);
    assertThat(crypto.encrypt("jdbc:postgresql://db.example.com/analytics"))
        .doesNotContain("db.example.com");
  }

  @Test
  void wrongKeyCannotDecrypt() {
    CredentialCrypto crypto = new CredentialCrypto(KEY);
    String stored = crypto.encrypt("secret");

    assertThatThrownBy(() -> new CredentialCrypto(OTHER_KEY).decrypt(stored))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void tamperedCiphertextFailsAuthentication() {
    CredentialCrypto crypto = new CredentialCrypto(KEY);
    byte[] raw = Base64.getDecoder().decode(crypto.encrypt("secret"));
    raw[raw.length - 1] ^= 0x01; // flip one bit: GCM tag must fail
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shortAndNonBase64KeysRejectedAtStartup() {
    assertThatThrownBy(() -> new CredentialCrypto("not-base64!!!"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
        () -> new CredentialCrypto(Base64.getEncoder().encodeToString("short".getBytes())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new CredentialCrypto(null)).isInstanceOf(IllegalStateException.class);
  }
}
