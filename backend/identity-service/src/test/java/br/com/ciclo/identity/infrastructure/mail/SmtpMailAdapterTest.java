package br.com.ciclo.identity.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import br.com.ciclo.identity.application.IdentityApplicationService.MailUnavailable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailAdapterTest {
  private final JavaMailSender sender = org.mockito.Mockito.mock(JavaMailSender.class);

  @Test
  void exposesOnlyMaskedSmtpMetadata() {
    var adapter =
        new SmtpMailAdapter(
            sender,
            "https://ciclo.example",
            "smtp.example.com",
            587,
            "mailer@example.com",
            "secret",
            "no-reply@example.com",
            true,
            true);

    var configuration = adapter.configuration();

    assertThat(configuration.mode()).isEqualTo("SMTP");
    assertThat(configuration.usernameMasked()).isEqualTo("ma••••om");
    assertThat(configuration.from()).isEqualTo("no-reply@example.com");
    assertThat(configuration.authentication()).isTrue();
    assertThat(configuration.startTls()).isTrue();
  }

  @Test
  void sendsTestMessageUsingConfiguredSender() {
    var adapter =
        new SmtpMailAdapter(
            sender,
            "http://localhost:4200",
            "mailpit",
            1025,
            "",
            "",
            "no-reply@ciclo.local",
            false,
            false);

    adapter.test("recipient@example.com");

    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(message.capture());
    assertThat(message.getValue().getFrom()).isEqualTo("no-reply@ciclo.local");
    assertThat(message.getValue().getTo()).containsExactly("recipient@example.com");
    assertThat(adapter.configuration().mode()).isEqualTo("LOCAL_CAPTURE");
  }

  @Test
  void translatesMailFailuresWithoutLeakingCredentials() {
    var adapter =
        new SmtpMailAdapter(
            sender,
            "http://localhost:4200",
            "smtp.example.com",
            587,
            "user",
            "secret",
            "no-reply@example.com",
            true,
            true);
    doThrow(new MailSendException("provider failure"))
        .when(sender)
        .send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.test("recipient@example.com"))
        .isInstanceOf(MailUnavailable.class)
        .hasMessageNotContaining("provider failure");
  }
}
