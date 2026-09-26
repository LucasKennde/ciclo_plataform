package br.com.ciclo.shared.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID correlationId,
    UUID causationId,
    Instant occurredAt,
    Map<String, Object> payload) {
  public static EventEnvelope create(
      String type, UUID correlationId, UUID causationId, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(), type, 1, correlationId, causationId, Instant.now(), Map.copyOf(payload));
  }
}
