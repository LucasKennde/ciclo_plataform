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
   * Sessão planejada. {@code startedAt} é o que permite ao pomodoro sobreviver a recarregar a
   * página ou trocar de aba: o cliente desconta a partir dele em vez de manter um cronômetro só na
   * memória. {@code sessions} é jsonb, então planos antigos simplesmente leem null aqui.
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
      Instant startedAt) {
    public StudySession start(Instant when) {
      return new StudySession(
          id, topicId, subjectName, topicName, date, minutes, kind, status, when);
    }

    public StudySession complete() {
      return new StudySession(
          id, topicId, subjectName, topicName, date, minutes, kind, "completed", startedAt);
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
      double averageAccuracy) {}
}
