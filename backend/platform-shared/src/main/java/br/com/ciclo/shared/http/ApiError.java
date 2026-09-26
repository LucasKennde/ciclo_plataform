package br.com.ciclo.shared.http;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    String code,
    String message,
    String correlationId,
    Instant timestamp,
    Map<String, String> fields) {
  public static ApiError of(String code, String message, String correlationId) {
    return new ApiError(code, message, correlationId, Instant.now(), Map.of());
  }
}
