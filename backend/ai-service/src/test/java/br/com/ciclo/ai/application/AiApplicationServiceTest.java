package br.com.ciclo.ai.application;

import static org.assertj.core.api.Assertions.*;

import br.com.ciclo.ai.application.AiApplicationService.*;
import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiApplicationServiceTest {

  /** Repository em memória: o ponto é exercitar as regras do serviço, não o JdbcTemplate. */
  static class FakeRepository implements Repository {
    final Map<Provider, Credential> credentials = new EnumMap<>(Provider.class);
    final List<Model> models = new ArrayList<>();
    final Map<Operation, Route> routes = new EnumMap<>(Operation.class);
    final List<String> audit = new ArrayList<>();

    @Override
    public Optional<Credential> credential(Provider p) {
      return Optional.ofNullable(credentials.get(p));
    }

    @Override
    public void saveCredential(Credential c) {
      credentials.put(c.provider(), c);
    }

    @Override
    public void deleteCredential(Provider p) {
      credentials.remove(p);
    }

    @Override
    public List<Model> models() {
      return List.copyOf(models);
    }

    @Override
    public void saveModel(Model model, String actor) {
      models.removeIf(m -> m.provider() == model.provider() && m.id().equals(model.id()));
      models.add(model);
      audit.add("MODEL_SAVED");
    }

    @Override
    public boolean deleteModel(Provider provider, String modelId) {
      return models.removeIf(m -> m.provider() == provider && m.id().equals(modelId));
    }

    @Override
    public Map<Operation, Route> routes() {
      return new EnumMap<>(routes);
    }

    @Override
    public void saveRoute(Route route, String actor) {
      routes.put(route.operation(), route);
      audit.add("ROUTE_CHANGED");
    }

    @Override
    public AiPolicy policy() {
      return AiPolicy.defaults();
    }

    @Override
    public void savePolicy(AiPolicy policy, String actor) {}

    @Override
    public void usage(Usage usage) {}

    @Override
    public Summary summary(Instant from) {
      return new Summary(0, 0, 0, 0, 0);
    }

    @Override
    public List<Usage> events(int limit) {
      return List.of();
    }

    @Override
    public List<Consumer> consumers(Instant from) {
      return List.of();
    }

    @Override
    public List<Block> blocks() {
      return List.of();
    }

    @Override
    public Optional<Block> activeBlock(String principal, Instant now) {
      return Optional.empty();
    }

    @Override
    public void block(Block block) {}

    @Override
    public void unblock(String principal) {}

    @Override
    public void audit(String action, String subject, String detail, String actor) {
      audit.add(action);
    }

    @Override
    public List<Audit> audit(int limit) {
      return List.of();
    }
  }

  static class RecordingGateway implements ProviderGateway {
    int tests;

    @Override
    public Result execute(
        Provider provider,
        String apiKey,
        String model,
        Operation operation,
        Map<String, Object> in) {
      return new Result(Map.of(), 0, 0);
    }

    @Override
    public TestResult test(Provider provider, String apiKey) {
      tests++;
      return new TestResult(true, null);
    }
  }

  private final FakeRepository repo = new FakeRepository();
  private final RecordingGateway gateway = new RecordingGateway();

  private record FakeCipher(boolean available) implements Cipher {
    @Override
    public String encrypt(String plain, Provider provider) {
      return "enc:" + plain;
    }

    @Override
    public String decrypt(String encrypted, Provider provider) {
      return encrypted.substring("enc:".length());
    }
  }

  private static final Counters ALLOW_ALL =
      new Counters() {
        @Override
        public Decision allow(String principal, AiPolicy policy) {
          return new Decision(true, null);
        }

        @Override
        public void addTokens(String principal, long tokens) {}
      };

  private AiApplicationService service(boolean masterKey, Map<Provider, String> envKeys) {
    return new AiApplicationService(
        repo,
        new FakeCipher(masterKey),
        gateway,
        ALLOW_ALL,
        key -> new byte[0],
        new Events() {
          @Override
          public void completed(UUID jobId, Map<String, Object> result) {}

          @Override
          public void failed(UUID jobId, String code, String message) {}
        },
        envKeys);
  }

  private AiApplicationService service() {
    return service(true, new EnumMap<>(Provider.class));
  }

  private ModelInput input(String id, double in, double out, List<String> operations) {
    return new ModelInput("openai", id, id, in, out, operations);
  }

  private void withOpenAiCredential() {
    repo.credentials.put(
        Provider.OPENAI,
        new Credential(
            Provider.OPENAI, "enc:sk-12345678", "5678", Instant.now(), "admin", true, null));
  }

  @Test
  void registersAModelTheAdminCanThenRouteTo() {
    var service = service();

    Overview overview =
        service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    assertThat(overview.providers()).filteredOn(p -> p.id() == Provider.OPENAI).hasSize(1);
    var openai =
        overview.providers().stream().filter(p -> p.id() == Provider.OPENAI).findFirst().get();
    assertThat(openai.models()).extracting(Model::id).containsExactly("gpt-4.1");
    assertThat(repo.audit).contains("MODEL_SAVED");
  }

  @Test
  void rejectsAModelWithoutOperations() {
    assertThatThrownBy(() -> service().registerModel(input("gpt-4.1", 1, 1, List.of()), "admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operação");
  }

  @Test
  void rejectsNegativePrices() {
    assertThatThrownBy(
            () ->
                service()
                    .registerModel(input("gpt-4.1", -1, 8, List.of("SYLLABUS_EXTRACTION")), "a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Preço de entrada");
  }

  @Test
  void appliesARouteToAModelThatIsRegistered() {
    var service = service();
    withOpenAiCredential();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    Overview overview =
        service.updateRoutes(
            List.of(new RouteInput("syllabus_extraction", "OpenAI", "gpt-4.1")), "admin");

    assertThat(overview.routing()).containsKey(Operation.SYLLABUS_EXTRACTION);
    Route route = overview.routing().get(Operation.SYLLABUS_EXTRACTION);
    assertThat(route.model()).isEqualTo("gpt-4.1");
    assertThat(route.inputPrice()).isEqualTo(2);
    assertThat(route.outputPrice()).isEqualTo(8);
  }

  @Test
  void rejectsARouteToAModelThatWasNeverRegistered() {
    var service = service();
    withOpenAiCredential();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    assertThatThrownBy(
            () ->
                service.updateRoutes(
                    List.of(new RouteInput("SYLLABUS_EXTRACTION", "OPENAI", "llama-3")), "admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("não está cadastrado");
  }

  @Test
  void validatesEveryRouteBeforePersistingAnyOfThem() {
    var service = service();
    withOpenAiCredential();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");
    service.registerModel(input("gpt-4o", 2, 8, List.of("MOCK_EXAM_EXTRACTION")), "admin");

    assertThatThrownBy(
            () ->
                service.updateRoutes(
                    List.of(
                        new RouteInput("SYLLABUS_EXTRACTION", "OPENAI", "gpt-4.1"),
                        new RouteInput("MOCK_EXAM_EXTRACTION", "OPENAI", "modelo-inexistente")),
                    "admin"))
        .isInstanceOf(IllegalArgumentException.class);

    // A rota válida do primeiro item não pode ter vazado para o banco.
    assertThat(repo.routes).isEmpty();
  }

  @Test
  void reportsAnUnknownOperationInsteadOfFailingWithANullPointer() {
    var service = service();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    assertThatThrownBy(
            () ->
                service.updateRoutes(
                    List.of(new RouteInput("NAO_EXISTE", "OPENAI", "gpt-4.1")), "a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Operação desconhecida");
  }

  @Test
  void reportsAnUnknownProviderInsteadOfFailingWithANullPointer() {
    assertThatThrownBy(
            () ->
                service()
                    .registerModel(
                        new ModelInput(null, "x", "x", 1, 1, List.of("SYLLABUS_EXTRACTION")), "a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provedor");
  }

  @Test
  void refusesToRemoveAModelStillUsedByARoute() {
    var service = service();
    withOpenAiCredential();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");
    service.updateRoutes(List.of(new RouteInput("SYLLABUS_EXTRACTION", "OPENAI", "gpt-4.1")), "a");

    assertThatThrownBy(() -> service.removeModel("OPENAI", "gpt-4.1", "admin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("antes de removê-lo");
    assertThat(repo.models).hasSize(1);
  }

  @Test
  void removesAnUnusedModel() {
    var service = service();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    Overview overview = service.removeModel("OPENAI", "gpt-4.1", "admin");

    assertThat(repo.models).isEmpty();
    assertThat(
            overview.providers().stream()
                .filter(p -> p.id() == Provider.OPENAI)
                .findFirst()
                .get()
                .models())
        .isEmpty();
  }

  @Test
  void doesNotInventRoutesForOperationsThatWereNeverConfigured() {
    assertThat(service().overview().routing()).isEmpty();
  }

  @Test
  void failsFastOnAMissingMasterKeyWithoutCallingTheProvider() {
    var envKeys = new EnumMap<Provider, String>(Provider.class);
    envKeys.put(Provider.OPENAI, "sk-from-env");

    // Sem a chave mestre o admin recebia um teste remoto e só depois o erro real, e o caminho
    // "salvar mesmo assim" repetia o mesmo teste sem chance de sucesso.
    assertThatThrownBy(
            () -> service(false, envKeys).setKey(Provider.OPENAI, "sk-12345678", "a", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AI_KEYS_MASTER_KEY");
    assertThat(gateway.tests).isZero();
  }

  @Test
  void requiresACredentialBeforeARouteCanBeApplied() {
    var service = service();
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    assertThatThrownBy(
            () ->
                service.updateRoutes(
                    List.of(new RouteInput("SYLLABUS_EXTRACTION", "OPENAI", "gpt-4.1")), "a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Salve a credencial");
  }

  @Test
  void allowsARouteForAProviderWhoseCredentialComesFromTheEnvironment() {
    var envKeys = new EnumMap<Provider, String>(Provider.class);
    envKeys.put(Provider.OPENAI, "sk-from-env");
    var service = service(true, envKeys);
    service.registerModel(input("gpt-4.1", 2, 8, List.of("SYLLABUS_EXTRACTION")), "admin");

    Overview overview =
        service.updateRoutes(
            List.of(new RouteInput("SYLLABUS_EXTRACTION", "OPENAI", "gpt-4.1")), "admin");

    assertThat(overview.routing()).containsKey(Operation.SYLLABUS_EXTRACTION);
  }
}
