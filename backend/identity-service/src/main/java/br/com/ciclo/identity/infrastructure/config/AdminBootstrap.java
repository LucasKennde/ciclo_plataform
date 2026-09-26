package br.com.ciclo.identity.infrastructure.config;

import br.com.ciclo.identity.application.IdentityApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
  private final IdentityApplicationService identity;
  private final String email;
  private final String password;

  public AdminBootstrap(
      IdentityApplicationService identity,
      @Value("${app.bootstrap-admin.email:}") String email,
      @Value("${app.bootstrap-admin.password:}") String password) {
    this.identity = identity;
    this.email = email;
    this.password = password;
  }

  public void run(ApplicationArguments args) {
    identity.bootstrapAdmin(email, password);
  }
}
