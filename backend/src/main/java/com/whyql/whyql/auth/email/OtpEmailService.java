package com.whyql.whyql.auth.email;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the 6-digit OTP by email. MailHog (localhost:1025) catches mail in dev;
 * configure a real SMTP host through SMTP_HOST/SMTP_PORT for other environments.
 * Transient SMTP failures are retried manually (3 attempts, 1s/2s backoff) so a
 * momentary outage does not fail registration — no spring-retry dependency needed.
 */
@Service
public class OtpEmailService {

  private static final int MAX_ATTEMPTS = 3;

  @Value("${whyql.mail.from:whyql@localhost}")
  private String from;

  private final JavaMailSender mailSender;

  public OtpEmailService(JavaMailSender mailSender) {
    this.mailSender = mailSender;
  }

  public void sendOtp(String to, String code) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(to);
    message.setSubject("Your WhyQL verification code");
    message.setText("Your 6-digit verification code is: " + code + "\n"
        + "It expires in 5 minutes. If you did not request this, ignore the email.");

    Exception last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        mailSender.send(message);
        return;
      } catch (Exception e) {
        last = e;
        if (attempt < MAX_ATTEMPTS) {
          sleep(Duration.ofSeconds(1L << (attempt - 1))); // 1s, then 2s
        }
      }
    }
    throw new AuthEmailException("failed to send OTP email to " + to + " after " + MAX_ATTEMPTS + " attempts", last);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthEmailException("interrupted while retrying OTP email", e);
    }
  }

  /** Wrapped so callers can decide (fail registration and surface EMAIL_SEND_FAILED). */
  public static class AuthEmailException extends RuntimeException {
    public AuthEmailException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
