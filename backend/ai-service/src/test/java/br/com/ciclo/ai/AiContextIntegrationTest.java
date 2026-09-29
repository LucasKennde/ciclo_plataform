package br.com.ciclo.ai;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.ai.application.AiPorts.Repository;
import br.com.ciclo.ai.domain.AiCatalog.Model;
import br.com.ciclo.ai.domain.AiCatalog.Operation;
import br.com.ciclo.ai.domain.AiCatalog.Provider;
import java.util.EnumSet;
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
  @Autowired Repository repository;

  @Test
  void runsFlywayAndProvidesTheJsonAdapter() {
    assertThat(json).isNotNull();
    assertThat(tableExists("flyway_schema_history")).isTrue();
    assertThat(tableExists("ai_usage")).isTrue();
    assertThat(tableExists("ai_routes")).isTrue();
  }

  @Test
  void seedsTheModelRegistrySoTheAdminCanRouteWithoutRegisteringAnythingFirst() {
    assertThat(tableExists("ai_models")).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_models where provider=?", Integer.class, "OPENAI"))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_models where operations not like '%SYLLABUS_EXTRACTION%'",
                Integer.class))
        .isZero();
  }

  /**
   * O SQL do repositório só é exercitado aqui: models() já voltou com "column id does not exist"
   * porque o ORDER BY usava id, e a coluna se chama model. A verificação por unidade do serviço não
   * pega isso, porque roda contra um repositório falso.
   */
  @Test
  void readsTheSeededModelsBack() {
    var models = repository.models();

    assertThat(models).isNotEmpty();
    assertThat(models).allSatisfy(m -> assertThat(m.operations()).isNotEmpty());
    assertThat(models)
        .filteredOn(m -> m.provider() == Provider.OPENAI)
        .extracting(Model::id)
        .contains("gpt-4.1");
  }

  @Test
  void roundTripsAModelThroughTheRegistry() {
    var model =
        new Model(
            Provider.GEMINI,
            "gemini-round-trip",
            "Round Trip",
            1.25,
            6,
            EnumSet.of(Operation.QUESTION_GENERATION, Operation.SYLLABUS_EXTRACTION),
            null,
            "admin");

    repository.saveModel(model, "admin");
    var stored =
        repository.models().stream()
            .filter(m -> m.provider() == Provider.GEMINI && m.id().equals("gemini-round-trip"))
            .findFirst()
            .orElseThrow();

    assertThat(stored.label()).isEqualTo("Round Trip");
    assertThat(stored.inputUsdPerMillion()).isEqualTo(1.25);
    assertThat(stored.operations())
        .containsExactlyInAnyOrder(Operation.QUESTION_GENERATION, Operation.SYLLABUS_EXTRACTION);
    assertThat(stored.updatedBy()).isEqualTo("admin");

    assertThat(repository.deleteModel(Provider.GEMINI, "gemini-round-trip")).isTrue();
    assertThat(repository.deleteModel(Provider.GEMINI, "gemini-round-trip")).isFalse();
  }

  @Test
  void overwritesAModelRegisteredTwiceInsteadOfFailing() {
    var first =
        new Model(
            Provider.OPENAI,
            "gpt-replaced",
            "Antes",
            1,
            1,
            EnumSet.of(Operation.QUESTION_GENERATION),
            null,
            "admin");
    var second =
        new Model(
            Provider.OPENAI,
            "gpt-replaced",
            "Depois",
            2,
            4,
            EnumSet.of(Operation.QUESTION_GENERATION),
            null,
            "admin");

    repository.saveModel(first, "admin");
    repository.saveModel(second, "admin");

    var stored =
        repository.models().stream()
            .filter(m -> m.provider() == Provider.OPENAI && m.id().equals("gpt-replaced"))
            .toList();
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).label()).isEqualTo("Depois");
    assertThat(stored.get(0).outputUsdPerMillion()).isEqualTo(4);

    repository.deleteModel(Provider.OPENAI, "gpt-replaced");
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
