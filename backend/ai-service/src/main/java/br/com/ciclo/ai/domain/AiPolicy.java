package br.com.ciclo.ai.domain;

public record AiPolicy(
    int perMinute,
    int perHour,
    int perDay,
    long perPrincipalTokensDay,
    int globalPerMinute,
    long globalTokensDay,
    boolean killSwitch) {
  public AiPolicy {
    if (perMinute < 1
        || perHour < perMinute
        || perDay < perHour
        || perPrincipalTokensDay < 1
        || globalPerMinute < 1
        || globalTokensDay < 1) throw new IllegalArgumentException("Limites de IA inválidos.");
  }

  public static AiPolicy defaults() {
    return new AiPolicy(10, 60, 300, 200_000, 120, 2_000_000, false);
  }
}
