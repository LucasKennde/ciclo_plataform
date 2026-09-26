package br.com.ciclo.identity.infrastructure.mail;

import br.com.ciclo.identity.application.IdentityApplicationService.MailUnavailable;
import br.com.ciclo.identity.application.IdentityPorts.Mail;
import br.com.ciclo.identity.application.IdentityPorts.MailConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailAdapter implements Mail {
  private final JavaMailSender sender;
  private final String webUrl;
  private final String host;
  private final int port;
  private final String username;
  private final boolean passwordConfigured;
  private final String from;
  private final boolean authentication;
  private final boolean startTls;

  public SmtpMailAdapter(
      JavaMailSender sender,
      @Value("${app.web-url:http://localhost:4200}") String webUrl,
      @Value("${spring.mail.host:localhost}") String host,
      @Value("${spring.mail.port:1025}") int port,
      @Value("${spring.mail.username:}") String username,
      @Value("${spring.mail.password:}") String password,
      @Value("${app.mail.from:no-reply@ciclo.local}") String from,
      @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean authentication,
      @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean startTls) {
    this.sender = sender;
    this.webUrl = webUrl;
    this.host = host;
    this.port = port;
    this.username = username;
    this.passwordConfigured = password != null && !password.isBlank();
    this.from = from;
    this.authentication = authentication;
    this.startTls = startTls;
  }

  public void verification(String email, String name, String token) {
    send(
        email,
        "Confirme seu e-mail no Ciclo",
        "Olá, " + name + ". Confirme sua conta: " + webUrl + "/verificar-email?token=" + token);
  }

  public void passwordReset(String email, String name, String token) {
    send(
        email,
        "Redefinição de senha do Ciclo",
        "Olá, " + name + ". Redefina sua senha: " + webUrl + "/redefinir-senha?token=" + token);
  }

  public void test(String recipient) {
    send(
        recipient,
        "Teste de envio do Ciclo",
        "O envio de e-mail do Ciclo está configurado corretamente.");
  }

  public MailConfiguration configuration() {
    boolean localCapture =
        (host.equalsIgnoreCase("mailpit") || host.equalsIgnoreCase("localhost")) && port == 1025;
    return new MailConfiguration(
        localCapture ? "LOCAL_CAPTURE" : "SMTP",
        !host.isBlank()
            && !from.isBlank()
            && (!authentication || (!username.isBlank() && passwordConfigured)),
        host,
        port,
        mask(username),
        from,
        authentication,
        startTls);
  }

  private void send(String to, String subject, String text) {
    try {
      var message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(to);
      message.setSubject(subject);
      message.setText(text);
      sender.send(message);
    } catch (MailException exception) {
      throw new MailUnavailable(
          "Não foi possível entregar o e-mail. Verifique a configuração SMTP.", exception);
    }
  }

  private String mask(String value) {
    if (value == null || value.isBlank()) return "";
    if (value.length() <= 4) return "••••";
    return value.substring(0, 2) + "••••" + value.substring(value.length() - 2);
  }
}
