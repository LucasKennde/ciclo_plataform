package br.com.ciclo.study;

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
class StudyContextIntegrationTest {
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
    assertThat(tableExists("competitions")).isTrue();
    assertThat(tableExists("study_plans")).isTrue();
    assertThat(tableExists("mock_exam_sources")).isTrue();
    assertThat(tableExists("onboarding_states")).isTrue();
    assertThat(columnExists("questions", "mock_exam_source_id")).isTrue();
  }

  private boolean tableExists(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from information_schema.tables where table_schema = 'public'"
                + " and table_name = ?)",
            Boolean.class,
            name));
  }

  private boolean columnExists(String table, String column) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from information_schema.columns where table_schema = 'public'"
                + " and table_name = ? and column_name = ?)",
            Boolean.class,
            table,
            column));
  }
}
