package br.com.ciclo.ai.presentation;

import br.com.ciclo.ai.application.*;
import br.com.ciclo.ai.application.AiApplicationService.*;
import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.*;
import br.com.ciclo.ai.domain.AiCatalog.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/ai")
public class AiAdminController {
  private final AiApplicationService ai;

  public AiAdminController(AiApplicationService ai) {
    this.ai = ai;
  }

  @GetMapping("/providers")
  Overview providers() {
    return ai.overview();
  }

  @PutMapping("/providers/{provider}/key")
  ProviderView key(
      @PathVariable Provider provider,
      @Valid @RequestBody KeyRequest r,
      @AuthenticationPrincipal Jwt jwt) {
    return ai.setKey(provider, r.apiKey(), jwt.getSubject(), r.force());
  }

  @DeleteMapping("/providers/{provider}/key")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable Provider provider, @AuthenticationPrincipal Jwt jwt) {
    ai.removeKey(provider, jwt.getSubject());
  }

  @PostMapping("/providers/{provider}/test")
  TestResult test(@PathVariable Provider provider) {
    return ai.test(provider);
  }

  @PutMapping("/routing")
  // A fronteira transacional fica na apresentação de propósito: a regra do ArchUnit proíbe
  // org.springframework na camada application. Sem ela, um lote com uma rota inválida no meio
  // deixava as rotas anteriores já gravadas.
  @Transactional
  Overview routes(
      @NotEmpty @Valid @RequestBody List<RouteRequest> routes, @AuthenticationPrincipal Jwt jwt) {
    return ai.updateRoutes(
        routes.stream().map(r -> new RouteInput(r.operation(), r.provider(), r.model())).toList(),
        jwt.getSubject());
  }

  @PostMapping("/models")
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  Overview saveModel(@Valid @RequestBody ModelRequest r, @AuthenticationPrincipal Jwt jwt) {
    return ai.registerModel(
        new ModelInput(
            r.provider(),
            r.id(),
            r.label(),
            r.inputUsdPerMillion().doubleValue(),
            r.outputUsdPerMillion().doubleValue(),
            r.operations()),
        jwt.getSubject());
  }

  @DeleteMapping("/models/{provider}/{modelId}")
  @Transactional
  @ResponseStatus(HttpStatus.OK)
  Overview removeModel(
      @PathVariable String provider,
      @PathVariable String modelId,
      @AuthenticationPrincipal Jwt jwt) {
    return ai.removeModel(provider, modelId, jwt.getSubject());
  }

  @GetMapping("/usage")
  Dashboard usage(@RequestParam(defaultValue = "24") int hours) {
    return ai.dashboard(hours);
  }

  @PatchMapping("/usage/settings")
  AiPolicy settings(@RequestBody AiPolicy policy, @AuthenticationPrincipal Jwt jwt) {
    return ai.updatePolicy(policy, jwt.getSubject());
  }

  @PostMapping("/usage/blocks")
  @ResponseStatus(HttpStatus.CREATED)
  void block(@Valid @RequestBody BlockRequest r, @AuthenticationPrincipal Jwt jwt) {
    ai.block(r.principal(), r.reason(), r.durationSeconds(), jwt.getSubject());
  }

  @DeleteMapping("/usage/blocks/{principal}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unblock(@PathVariable String principal, @AuthenticationPrincipal Jwt jwt) {
    ai.unblock(principal, jwt.getSubject());
  }

  @GetMapping("/audit")
  List<Audit> audit() {
    return ai.audit();
  }

  record KeyRequest(@NotBlank String apiKey, boolean force) {}

  record RouteRequest(
      @NotBlank String operation, @NotBlank String provider, @NotBlank String model) {}

  record ModelRequest(
      @NotBlank String provider,
      @NotBlank @Size(max = 120) String id,
      @NotBlank @Size(max = 120) String label,
      @NotNull @PositiveOrZero Double inputUsdPerMillion,
      @NotNull @PositiveOrZero Double outputUsdPerMillion,
      @NotEmpty List<@NotBlank String> operations) {}

  record BlockRequest(@NotBlank String principal, @NotBlank String reason, Long durationSeconds) {}
}
