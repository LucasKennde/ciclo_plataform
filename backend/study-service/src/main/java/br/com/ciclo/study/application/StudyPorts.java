package br.com.ciclo.study.application;

import br.com.ciclo.study.domain.Competition;
import java.time.*;
import java.util.*;

public final class StudyPorts {
  private StudyPorts() {}

  public interface Competitions {
    Competition save(Competition value);

    Optional<Competition> find(UUID workspaceId, UUID id);

    List<Competition> list(UUID workspaceId);

    void delete(UUID workspaceId, UUID id);
  }

  public interface Store {
    Document saveDocument(Document value);

    List<Document> documents(UUID workspaceId, UUID competitionId);

    Optional<Document> document(UUID workspaceId, UUID id);

    MockExamSource saveMockExamSource(MockExamSource value);

    Optional<MockExamSource> mockExamSource(UUID workspaceId, UUID id);

    List<MockExamSource> mockExamSources(UUID workspaceId, UUID competitionId);

    Job saveJob(Job value);

    Optional<Job> job(UUID workspaceId, UUID id);

    Optional<Job> latestJob(UUID workspaceId, UUID aggregateId, String type);

    Syllabus saveSyllabus(Syllabus value);

    Optional<Syllabus> syllabus(UUID workspaceId, UUID competitionId);

    Plan savePlan(Plan value);

    Plan savePlanAndCompleteOnboarding(Plan value);

    Optional<Plan> plan(UUID workspaceId, UUID competitionId);

    Flashcard saveFlashcard(Flashcard value);

    List<Flashcard> flashcards(UUID workspaceId, UUID competitionId);

    void deleteFlashcard(UUID workspaceId, UUID id);

    Question saveQuestion(Question value);

    List<Question> questions(UUID workspaceId, UUID competitionId);

    List<Question> questionsByMockExamSource(UUID workspaceId, UUID sourceId);

    Simulation saveSimulation(Simulation value);

    Optional<Simulation> simulation(UUID workspaceId, UUID id);

    List<Simulation> simulations(UUID workspaceId, UUID competitionId);
  }

  public interface Onboardings {
    Optional<OnboardingState> find(UUID workspaceId);

    OnboardingState save(OnboardingState value);
  }

  public interface Objects {
    void put(String key, byte[] bytes, String contentType);

    byte[] get(String key);
  }

  public interface Events {
    void publish(String type, UUID correlationId, Map<String, Object> payload);
  }

  public record Document(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      int version,
      String fileName,
      String objectKey,
      long sizeBytes,
      String status,
      Instant createdAt) {}

  public record MockExamSource(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      int version,
      String fileName,
      String objectKey,
      long sizeBytes,
      String status,
      String board,
      int questionCount,
      Instant createdAt,
      Instant updatedAt) {}

  public record Job(
      UUID id,
      UUID workspaceId,
      String type,
      UUID aggregateId,
      String status,
      int progress,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant updatedAt) {}

  public record OnboardingState(
      UUID workspaceId,
      UUID competitionId,
      Instant dismissedAt,
      Instant completedAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record Syllabus(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      UUID documentId,
      int version,
      String status,
      List<SyllabusSubject> subjects,
      Instant approvedAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record Plan(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      int version,
      List<Availability> availability,
      List<StudySession> sessions,
      Instant createdAt,
      Instant updatedAt) {}

  public record Flashcard(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      String front,
      String back,
      Instant createdAt,
      Instant updatedAt) {}

  public record Question(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      UUID mockExamSourceId,
      String topicId,
      String origin,
      String status,
      String boardStyle,
      int difficulty,
      String statement,
      List<String> alternatives,
      int correctIndex,
      String explanation,
      List<String> sources,
      Instant createdAt,
      Instant updatedAt) {}

  public record Simulation(
      UUID id,
      UUID workspaceId,
      UUID competitionId,
      String title,
      String status,
      List<SimulationItem> items,
      int durationMinutes,
      Instant startedAt,
      Instant finishedAt) {}

  public record SyllabusSubject(String id, String name, double weight, List<SyllabusTopic> topics) {
    public SyllabusSubject {
      topics = topics == null ? List.of() : List.copyOf(topics);
    }
  }

  public record SyllabusTopic(
      String id, String name, double weight, Evidence evidence, List<SyllabusTopic> children) {
    public SyllabusTopic {
      children = children == null ? List.of() : List.copyOf(children);
    }
  }

  public record Evidence(Integer page, String excerpt, double confidence) {}

  public record Availability(int weekday, int minutes) {}

  /**
   * Sessão planejada e o pomodoro dela.
   *
   * <p>A invariante é sempre a mesma e vale para todos os estados:
   *
   * <pre>
   *   investido = accumulatedSeconds + (startedAt == null ? 0 : now - startedAt)
   *   restante  = minutes * 60 - investido
   * </pre>
   *
   * <p>{@code startedAt} é a âncora no servidor: é por causa dela que o cronômetro sobrevive a
   * recarregar a página, trocar de aba e o usuário sair para estudar no celular. {@code
   * accumulatedSeconds} guarda o que foi investido antes da última pausa — sem ele, pausar seria só
   * cosmetics e o tempo voltaria ao recarregar. {@code sessions} é jsonb, então planos antigos leem
   * {@code null}/{@code 0} aqui e caem em "planned" sem nenhuma migração.
   */
  public record StudySession(
      UUID id,
      String topicId,
      String subjectName,
      String topicName,
      LocalDate date,
      int minutes,
      String kind,
      String status,
      Instant startedAt,
      Integer accumulatedSeconds) {

    public static final String PLANNED = "planned";
    public static final String IN_PROGRESS = "in_progress";
    public static final String PAUSED = "paused";
    public static final String COMPLETED = "completed";
    public static final String MISSED = "missed";

    public StudySession {
      accumulatedSeconds = accumulatedSeconds == null ? 0 : Math.max(0, accumulatedSeconds);
    }

    /** Segundos já investidos, incluindo a execução em curso. */
    public long investedSeconds(Instant now) {
      long running =
          startedAt == null ? 0 : Math.max(0, now.getEpochSecond() - startedAt.getEpochSecond());
      return accumulatedSeconds + running;
    }

    public long remainingSeconds(Instant now) {
      return Math.max(0, (long) minutes * 60 - investedSeconds(now));
    }

    /**
     * Relógio em curso. Não exige o status in_progress porque os planos gravados antes deste modelo
     * carregam "planned" com startedAt preenchido — e esses precisam continuar contando.
     */
    public boolean running() {
      return startedAt != null && !COMPLETED.equals(status) && !MISSED.equals(status);
    }

    public StudySession start(Instant when) {
      // Retomar uma sessão pausada não devolve tempo: accumulatedSeconds fica.
      return new StudySession(
          id,
          topicId,
          subjectName,
          topicName,
          date,
          minutes,
          kind,
          IN_PROGRESS,
          when,
          accumulatedSeconds);
    }

    public StudySession pause(Instant now) {
      if (startedAt == null) return this;
      return new StudySession(
          id,
          topicId,
          subjectName,
          topicName,
          date,
          minutes,
          kind,
          PAUSED,
          null,
          (int)
              Math.min(
                  Integer.MAX_VALUE,
                  accumulatedSeconds
                      + Math.max(0, now.getEpochSecond() - startedAt.getEpochSecond())));
    }

    /** Concluir encerra o cronômetro: o investido vira o que foi de fato estudado. */
    public StudySession complete(Instant now) {
      long invested = investedSeconds(now);
      return new StudySession(
          id,
          topicId,
          subjectName,
          topicName,
          date,
          minutes,
          kind,
          COMPLETED,
          null,
          (int) Math.min(Integer.MAX_VALUE, invested));
    }

    public boolean missed() {
      return MISSED.equals(status);
    }

    public StudySession miss() {
      return new StudySession(
          id,
          topicId,
          subjectName,
          topicName,
          date,
          minutes,
          kind,
          MISSED,
          null,
          accumulatedSeconds);
    }
  }

  public record SimulationItem(
      UUID questionId,
      String topicId,
      int correctIndex,
      Integer selectedIndex,
      Instant answeredAt) {
    public SimulationItem answer(int selectedIndex, Instant answeredAt) {
      return new SimulationItem(questionId, topicId, correctIndex, selectedIndex, answeredAt);
    }
  }

  public record GeneratedQuestion(
      String topicId,
      String boardStyle,
      int difficulty,
      String statement,
      List<String> alternatives,
      int correctIndex,
      String explanation) {
    public GeneratedQuestion {
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
  }

  public record Classification(UUID questionId, String topicId) {}

  public record AiResult(
      List<SyllabusSubject> subjects,
      List<GeneratedQuestion> questions,
      List<Classification> assignments,
      String board) {
    public AiResult {
      subjects = subjects == null ? List.of() : List.copyOf(subjects);
      questions = questions == null ? List.of() : List.copyOf(questions);
      assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
  }

  public record Progress(
      int xp,
      int level,
      int streak,
      int completedSessions,
      int totalAnswered,
      double averageAccuracy,
      int missedSessions,
      long focusedMinutes) {}
}
