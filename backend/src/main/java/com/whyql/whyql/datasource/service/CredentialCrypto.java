package com.whyql.whyql.datasource.service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM-256 encryption for stored database credentials, keyed by the
 * ENCRYPTION_KEY env var (32 random bytes, Base64). A fresh 12-byte IV per value
 * is prepended to the ciphertext; the stored form is Base64(iv || ciphertext).
 * The key is validated at startup so a misconfigured deploy fails fast instead of
 * writing undecryptable rows. JDK built-in JCE only, no extra dependency.
 */
@Component
public class CredentialCrypto {

  private static final int IV_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private final SecretKeySpec key;

  public CredentialCrypto(@Value("${ENCRYPTION_KEY}") String base64Key) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("ENCRYPTION_KEY must be Base64", e);
    }
    if (raw.length != 32) {
      throw new IllegalStateException("ENCRYPTION_KEY must decode to exactly 32 bytes");
    }
    this.key = new SecretKeySpec(raw, "AES");
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] combined = new byte[IV_BYTES + cipherBytes.length];
      System.arraycopy(iv, 0, combined, 0, IV_BYTES);
      System.arraycopy(cipherBytes, 0, combined, IV_BYTES, cipherBytes.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new IllegalStateException("credential encryption failed", e);
    }
  }

  public String decrypt(String stored) {
    try {
      byte[] combined = Base64.getDecoder().decode(stored);
      byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
      byte[] cipherBytes = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
      return new String(cipher.doFinal(cipherBytes), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("credential decryption failed", e);
    }
  }
}
