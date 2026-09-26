package br.com.ciclo.ai.infrastructure.limits;

import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.AiPolicy;
import java.time.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAiCounters implements Counters {
  private final StringRedisTemplate redis;

  public RedisAiCounters(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Decision allow(String principal, AiPolicy p) {
    long minute = increment("ai:p:m:" + principal + ":" + bucket(60), Duration.ofMinutes(2));
    if (minute > p.perMinute()) return new Decision(false, "PER_MINUTE");
    long hour = increment("ai:p:h:" + principal + ":" + bucket(3600), Duration.ofHours(2));
    if (hour > p.perHour()) return new Decision(false, "PER_HOUR");
    long day =
        increment("ai:p:d:" + principal + ":" + LocalDate.now(ZoneOffset.UTC), Duration.ofDays(2));
    if (day > p.perDay()) return new Decision(false, "PER_DAY");
    long global = increment("ai:g:m:" + bucket(60), Duration.ofMinutes(2));
    if (global > p.globalPerMinute()) return new Decision(false, "GLOBAL_PER_MINUTE");
    long tokens = value("ai:p:t:" + principal + ":" + LocalDate.now(ZoneOffset.UTC));
    if (tokens >= p.perPrincipalTokensDay()) return new Decision(false, "PRINCIPAL_TOKENS_DAY");
    long globalTokens = value("ai:g:t:" + LocalDate.now(ZoneOffset.UTC));
    if (globalTokens >= p.globalTokensDay()) return new Decision(false, "GLOBAL_TOKENS_DAY");
    return new Decision(true, null);
  }

  public void addTokens(String principal, long tokens) {
    add("ai:p:t:" + principal + ":" + LocalDate.now(ZoneOffset.UTC), tokens);
    add("ai:g:t:" + LocalDate.now(ZoneOffset.UTC), tokens);
  }

  private long increment(String key, Duration ttl) {
    Long value = redis.opsForValue().increment(key);
    if (value != null && value == 1) redis.expire(key, ttl);
    return value == null ? 0 : value;
  }

  private void add(String key, long value) {
    redis.opsForValue().increment(key, value);
    redis.expire(key, Duration.ofDays(2));
  }

  private long value(String key) {
    String value = redis.opsForValue().get(key);
    return value == null ? 0 : Long.parseLong(value);
  }

  private long bucket(long seconds) {
    return Instant.now().getEpochSecond() / seconds;
  }
}
