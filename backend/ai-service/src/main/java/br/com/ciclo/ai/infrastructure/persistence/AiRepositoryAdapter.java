package br.com.ciclo.ai.infrastructure.persistence;

import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import br.com.ciclo.ai.domain.AiPolicy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AiRepositoryAdapter implements Repository {
  private final JdbcTemplate db;

  public AiRepositoryAdapter(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<Credential> credential(Provider p) {
    return db
        .query(
            "SELECT * FROM ai_credentials WHERE provider=?",
            (r, n) ->
                new Credential(
                    p,
                    r.getString("api_key_enc"),
                    r.getString("last4"),
                    at(r, "updated_at"),
                    r.getString("updated_by"),
                    (Boolean) r.getObject("last_test_ok"),
                    r.getString("last_test_error")),
            p.name())
        .stream()
        .findFirst();
  }

  public void saveCredential(Credential c) {
    db.update(
        "INSERT INTO"
            + " ai_credentials(provider,api_key_enc,last4,updated_at,updated_by,last_test_ok,last_test_error)VALUES(?,?,?,?,?,?,?)"
            + " ON CONFLICT(provider)DO UPDATE SET"
            + " api_key_enc=excluded.api_key_enc,last4=excluded.last4,updated_at=excluded.updated_at,updated_by=excluded.updated_by,last_test_ok=excluded.last_test_ok,last_test_error=excluded.last_test_error",
        c.provider().name(),
        c.encryptedKey(),
        c.last4(),
        ts(c.updatedAt()),
        c.updatedBy(),
        c.lastTestOk(),
        c.lastTestError());
  }

  public void deleteCredential(Provider p) {
    db.update("DELETE FROM ai_credentials WHERE provider=?", p.name());
  }

  public List<Model> models() {
    return db.query(
        "SELECT * FROM ai_models ORDER BY provider,id",
        (r, n) ->
            new Model(
                Provider.valueOf(r.getString("provider")),
                r.getString("model"),
                r.getString("label"),
                r.getDouble("input_price"),
                r.getDouble("output_price"),
                readOperations(r.getString("operations")),
                at(r, "updated_at"),
                r.getString("updated_by")));
  }

  public void saveModel(Model m, String actor) {
    db.update(
        "INSERT INTO"
            + " ai_models(provider,model,label,input_price,output_price,operations,created_at,updated_at,updated_by)VALUES(?,?,?,?,?,?,now(),now(),?)"
            + " ON CONFLICT(provider,model)DO UPDATE SET"
            + " label=excluded.label,input_price=excluded.input_price,output_price=excluded.output_price,operations=excluded.operations,updated_at=excluded.updated_at,updated_by=excluded.updated_by",
        m.provider().name(),
        m.id(),
        m.label(),
        m.inputUsdPerMillion(),
        m.outputUsdPerMillion(),
        writeOperations(m.operations()),
        actor);
  }

  public boolean deleteModel(Provider p, String modelId) {
    return db.update("DELETE FROM ai_models WHERE provider=? AND model=?", p.name(), modelId) > 0;
  }

  private static Set<Operation> readOperations(String csv) {
    if (csv == null || csv.isBlank()) return EnumSet.noneOf(Operation.class);
    Set<Operation> out = EnumSet.noneOf(Operation.class);
    for (String raw : csv.split(",")) {
      String name = raw.trim();
      if (name.isEmpty()) continue;
      try {
        out.add(Operation.valueOf(name));
      } catch (IllegalArgumentException e) {
        // Operação desconhecida em uma linha gravada por uma versão anterior: melhor ignorar
        // do que derrubar a tela inteira de configuração por causa dela.
        continue;
      }
    }
    return out;
  }

  private static String writeOperations(Set<Operation> operations) {
    return operations.stream()
        .sorted(Comparator.comparingInt(Enum::ordinal))
        .map(Enum::name)
        .collect(Collectors.joining(","));
  }

  public Map<Operation, Route> routes() {
    Map<Operation, Route> out = new EnumMap<>(Operation.class);
    db.query(
        "SELECT * FROM ai_routes",
        r -> {
          var op = Operation.valueOf(r.getString("operation"));
          out.put(
              op,
              new Route(
                  op,
                  Provider.valueOf(r.getString("provider")),
                  r.getString("model"),
                  r.getDouble("input_price"),
                  r.getDouble("output_price")));
        });
    return out;
  }

  public void saveRoute(Route r, String actor) {
    db.update(
        "INSERT INTO"
            + " ai_routes(operation,provider,model,input_price,output_price,updated_at,updated_by)VALUES(?,?,?,?,?,?,?)"
            + " ON CONFLICT(operation)DO UPDATE SET"
            + " provider=excluded.provider,model=excluded.model,input_price=excluded.input_price,output_price=excluded.output_price,updated_at=excluded.updated_at,updated_by=excluded.updated_by",
        r.operation().name(),
        r.provider().name(),
        r.model(),
        r.inputPrice(),
        r.outputPrice(),
        Timestamp.from(Instant.now()),
        actor);
  }

  public AiPolicy policy() {
    return db
        .query(
            "SELECT * FROM ai_policy WHERE id=1",
            (r, n) ->
                new AiPolicy(
                    r.getInt("per_minute"),
                    r.getInt("per_hour"),
                    r.getInt("per_day"),
                    r.getLong("per_principal_tokens_day"),
                    r.getInt("global_per_minute"),
                    r.getLong("global_tokens_day"),
                    r.getBoolean("kill_switch")))
        .stream()
        .findFirst()
        .orElse(AiPolicy.defaults());
  }

  public void savePolicy(AiPolicy p, String actor) {
    db.update(
        "INSERT INTO"
            + " ai_policy(id,per_minute,per_hour,per_day,per_principal_tokens_day,global_per_minute,global_tokens_day,kill_switch,updated_at,updated_by)VALUES(1,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT(id)DO UPDATE SET"
            + " per_minute=excluded.per_minute,per_hour=excluded.per_hour,per_day=excluded.per_day,per_principal_tokens_day=excluded.per_principal_tokens_day,global_per_minute=excluded.global_per_minute,global_tokens_day=excluded.global_tokens_day,kill_switch=excluded.kill_switch,updated_at=excluded.updated_at,updated_by=excluded.updated_by",
        p.perMinute(),
        p.perHour(),
        p.perDay(),
        p.perPrincipalTokensDay(),
        p.globalPerMinute(),
        p.globalTokensDay(),
        p.killSwitch(),
        Timestamp.from(Instant.now()),
        actor);
  }

  public void usage(Usage u) {
    db.update(
        "INSERT INTO"
            + " ai_usage(id,request_id,principal,operation,provider,model,outcome,rule_code,input_tokens,output_tokens,cost_usd,duration_ms,created_at)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        u.id(),
        u.requestId(),
        u.principal(),
        u.operation().name(),
        u.provider().name(),
        u.model(),
        u.outcome(),
        u.ruleCode(),
        u.inputTokens(),
        u.outputTokens(),
        u.costUsd(),
        u.durationMs(),
        ts(u.createdAt()));
  }

  public Summary summary(Instant from) {
    return db.query(
            "SELECT"
                + " count(*),coalesce(sum(input_tokens+output_tokens),0),coalesce(sum(cost_usd),0),count(*)FILTER(WHERE"
                + " outcome='BLOCKED'),count(*)FILTER(WHERE outcome='ERROR')FROM ai_usage WHERE"
                + " created_at>=?",
            (r, n) ->
                new Summary(r.getLong(1), r.getLong(2), r.getDouble(3), r.getLong(4), r.getLong(5)),
            ts(from))
        .get(0);
  }

  public List<Usage> events(int limit) {
    return db.query(
        "SELECT * FROM ai_usage ORDER BY created_at DESC LIMIT ?", this::mapUsage, limit);
  }

  public List<Consumer> consumers(Instant from) {
    return db.query(
        "SELECT"
            + " principal,count(*),coalesce(sum(input_tokens+output_tokens),0),coalesce(sum(cost_usd),0),count(*)FILTER(WHERE"
            + " outcome='BLOCKED')FROM ai_usage WHERE created_at>=? GROUP BY principal ORDER BY 3"
            + " DESC LIMIT 50",
        (r, n) ->
            new Consumer(r.getString(1), r.getLong(2), r.getLong(3), r.getDouble(4), r.getLong(5)),
        ts(from));
  }

  public List<Block> blocks() {
    return db.query("SELECT * FROM ai_blocks ORDER BY created_at DESC", this::mapBlock);
  }

  public Optional<Block> activeBlock(String principal, Instant now) {
    return db
        .query(
            "SELECT * FROM ai_blocks WHERE principal=? AND (blocked_until IS NULL OR"
                + " blocked_until>?)",
            this::mapBlock,
            principal,
            ts(now))
        .stream()
        .findFirst();
  }

  public void block(Block b) {
    db.update(
        "INSERT INTO"
            + " ai_blocks(principal,reason,blocked_until,created_by,created_at)VALUES(?,?,?,?,?) ON"
            + " CONFLICT(principal)DO UPDATE SET"
            + " reason=excluded.reason,blocked_until=excluded.blocked_until,created_by=excluded.created_by,created_at=excluded.created_at",
        b.principal(),
        b.reason(),
        ts(b.blockedUntil()),
        b.createdBy(),
        ts(b.createdAt()));
  }

  public void unblock(String principal) {
    db.update("DELETE FROM ai_blocks WHERE principal=?", principal);
  }

  public void audit(String action, String subject, String detail, String actor) {
    db.update(
        "INSERT INTO ai_audit(action,subject,detail,actor,created_at)VALUES(?,?,?,?,?)",
        action,
        subject,
        detail,
        actor,
        Timestamp.from(Instant.now()));
  }

  public List<Audit> audit(int limit) {
    return db.query(
        "SELECT * FROM ai_audit ORDER BY created_at DESC LIMIT ?",
        (r, n) ->
            new Audit(
                r.getLong("id"),
                r.getString("action"),
                r.getString("subject"),
                r.getString("detail"),
                r.getString("actor"),
                at(r, "created_at")),
        limit);
  }

  private Usage mapUsage(ResultSet r, int n) throws SQLException {
    return new Usage(
        (UUID) r.getObject("id"),
        (UUID) r.getObject("request_id"),
        r.getString("principal"),
        Operation.valueOf(r.getString("operation")),
        Provider.valueOf(r.getString("provider")),
        r.getString("model"),
        r.getString("outcome"),
        r.getString("rule_code"),
        r.getLong("input_tokens"),
        r.getLong("output_tokens"),
        r.getDouble("cost_usd"),
        r.getLong("duration_ms"),
        at(r, "created_at"));
  }

  private Block mapBlock(ResultSet r, int n) throws SQLException {
    return new Block(
        r.getString("principal"),
        r.getString("reason"),
        at(r, "blocked_until"),
        r.getString("created_by"),
        at(r, "created_at"));
  }

  private static Instant at(ResultSet r, String c) throws SQLException {
    Timestamp t = r.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
