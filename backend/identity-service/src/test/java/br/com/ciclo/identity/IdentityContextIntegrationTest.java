package br.com.ciclo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.identity.infrastructure.config.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {
      "app.bootstrap-admin.email=admin@ciclo.local",
      "app.bootstrap-admin.password=ChangeMe123!"
    })
@Testcontainers(disabledWithoutDocker = true)
class IdentityContextIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired AdminBootstrap bootstrap;

  @Test
  void runsFlywayBeforeJpaAndCreatesTheBootstrapAdministratorOnlyOnce() throws Exception {
    assertThat(tableExists("flyway_schema_history")).isTrue();
    assertThat(tableExists("users")).isTrue();
    assertThat(tableExists("identity_settings")).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select email_verification_required from identity_settings where id = 1",
                Boolean.class))
        .isFalse();
    assertThat(adminCount()).isOne();

    bootstrap.run(null);

    assertThat(adminCount()).isOne();
    assertThat(
            jdbc.queryForObject(
                "select role from users where email = ?", String.class, "admin@ciclo.local"))
        .isEqualTo("ADMIN");
  }

  private boolean tableExists(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from information_schema.tables where table_schema = 'public'"
                + " and table_name = ?)",
            Boolean.class,
            name));
  }

  private long adminCount() {
    return jdbc.queryForObject(
        "select count(*) from users where email = ?", Long.class, "admin@ciclo.local");
  }
}
