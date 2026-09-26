package br.com.ciclo.admin.infrastructure;

import br.com.ciclo.admin.application.AdminPorts.*;
import br.com.ciclo.admin.domain.PlatformSettings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminPersistenceAdapter implements Repository {
  private final JdbcTemplate db;

  public AdminPersistenceAdapter(JdbcTemplate db) {
    this.db = db;
  }

  public PlatformSettings settings() {
    return db.query("SELECT * FROM platform_settings WHERE id=1", (r, n) -> settings(r)).get(0);
  }

  public PlatformSettings saveSettings(PlatformSettings value, String actor, Instant updatedAt) {
    db.update(
        "UPDATE platform_settings SET"
            + " product_name=?,support_email=?,description=?,locale=?,timezone=?,maintenance_mode=?,updated_at=?,updated_by=?"
            + " WHERE id=1",
        value.productName(),
        value.supportEmail(),
        value.description(),
        value.locale(),
        value.timezone(),
        value.maintenanceMode(),
        Timestamp.from(updatedAt),
        actor);
    return settings();
  }

  public void audit(String action, String subject, String detail, String actor, Instant createdAt) {
    db.update(
        "INSERT INTO admin_audit(action,subject,detail,actor,created_at)VALUES(?,?,?,?,?)",
        action,
        subject,
        detail,
        actor,
        Timestamp.from(createdAt));
  }

  public List<Audit> audit(int limit) {
    return db.query(
        "SELECT * FROM admin_audit ORDER BY created_at DESC LIMIT ?",
        (r, n) ->
            new Audit(
                r.getLong("id"),
                r.getString("action"),
                r.getString("subject"),
                r.getString("detail"),
                r.getString("actor"),
                r.getTimestamp("created_at").toInstant()),
        limit);
  }

  private static PlatformSettings settings(java.sql.ResultSet r) throws java.sql.SQLException {
    return new PlatformSettings(
        r.getString("product_name"),
        r.getString("support_email"),
        r.getString("description"),
        r.getString("locale"),
        r.getString("timezone"),
        r.getBoolean("maintenance_mode"),
        r.getTimestamp("updated_at").toInstant(),
        r.getString("updated_by"));
  }
}
