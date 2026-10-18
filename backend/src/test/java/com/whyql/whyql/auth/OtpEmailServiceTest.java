package com.whyql.whyql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.whyql.whyql.auth.email.OtpEmailService;

/**
 * Plain unit test for the OTP email retry loop: first-try success, retry-then-success,
 * and failure after the last attempt.
 */
class OtpEmailServiceTest {

  @Test
  void sendsOnFirstAttemptWithoutRetrying() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    OtpEmailService service = new OtpEmailService(mailSender);

    service.sendOtp("alice@example.com", "123456");

    verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
  }

  @Test
  void retriesUntilSucceeds() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    doThrow(new RuntimeException("smtp down"))
        .doThrow(new RuntimeException("smtp down"))
        .doNothing()
        .when(mailSender)
        .send(any(SimpleMailMessage.class));

    OtpEmailService service = new OtpEmailService(mailSender);

    service.sendOtp("alice@example.com", "123456");
    verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
  }

  @Test
  void throwsAuthEmailExceptionAfterAllAttempts() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

    OtpEmailService service = new OtpEmailService(mailSender);

    assertThatThrownBy(() -> service.sendOtp("alice@example.com", "123456"))
        .isInstanceOf(OtpEmailService.AuthEmailException.class)
        .hasMessageContaining("after 3 attempts");
    verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
  }
}
