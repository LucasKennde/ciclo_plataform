package br.com.ciclo.admin.infrastructure;

import br.com.ciclo.admin.application.AdminPorts.*;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AdminHttpClients implements IdentityClient, AiClient {
  private final RestClient http;
  private final String identityUrl;
  private final String aiUrl;

  public AdminHttpClients(
      RestClient.Builder http,
      @Value("${app.identity-url}") String identityUrl,
      @Value("${app.ai-url}") String aiUrl) {
    this.http = http.build();
    this.identityUrl = identityUrl;
    this.aiUrl = aiUrl;
  }

  public OptionalLong userCount(String authorization) {
    try {
      UsersResponse response =
          http.get()
              .uri(identityUrl + "/api/admin/v1/users?size=1")
              .header(HttpHeaders.AUTHORIZATION, authorization)
              .retrieve()
              .body(UsersResponse.class);
      return response == null ? OptionalLong.empty() : OptionalLong.of(response.total());
    } catch (Exception ignored) {
      return OptionalLong.empty();
    }
  }

  public Optional<AiSummary> usageSummary(String authorization, int hours) {
    try {
      UsageResponse response =
          http.get()
              .uri(aiUrl + "/api/admin/v1/ai/usage?hours=" + hours)
              .header(HttpHeaders.AUTHORIZATION, authorization)
              .retrieve()
              .body(UsageResponse.class);
      return response == null ? Optional.empty() : Optional.ofNullable(response.summary());
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private record UsersResponse(long total) {}

  private record UsageResponse(AiSummary summary) {}
}
