package br.com.ciclo.admin.presentation;

import br.com.ciclo.admin.application.AdminApplicationService;
import br.com.ciclo.admin.application.AdminPorts.Audit;
import br.com.ciclo.admin.application.AdminPorts.Dashboard;
import br.com.ciclo.admin.domain.PlatformSettings;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1")
public class AdminController {
  private final AdminApplicationService admin;

  public AdminController(AdminApplicationService admin) {
    this.admin = admin;
  }

  @GetMapping("/dashboard")
  Dashboard dashboard(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
    return admin.dashboard(authorization);
  }

  @GetMapping("/settings")
  PlatformSettings settings() {
    return admin.settings();
  }

  @PutMapping("/settings")
  PlatformSettings update(
      @Valid @RequestBody PlatformSettings settings, @AuthenticationPrincipal Jwt jwt) {
    return admin.update(settings, jwt.getSubject());
  }

  @GetMapping("/security/audit")
  List<Audit> audit() {
    return admin.audit();
  }
}
