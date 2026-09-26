package br.com.ciclo.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers(disabledWithoutDocker = true)
class AiContextIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;

  @Test
  void runsFlywayAndProvidesTheJsonAdapter() {
    assertThat(json).isNotNull();
    assertThat(tableExists("flyway_schema_history")).isTrue();
    assertThat(tableExists("ai_usage")).isTrue();
    assertThat(tableExists("ai_routes")).isTrue();
  }

  private boolean tableExists(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from information_schema.tables where table_schema = 'public'"
                + " and table_name = ?)",
            Boolean.class,
            name));
  }
}
