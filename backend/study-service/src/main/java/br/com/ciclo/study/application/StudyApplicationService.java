package br.com.ciclo.study.application;

import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.UnaryOperator;

public class StudyApplicationService {
  /** Abaixo disso não é sessão de estudo; 50 min é o teto de atenção que o plano assume. */
  private static final int MIN_SESSION_MINUTES = 25;

  private static final int MAX_SESSION_MINUTES = 50;

  private final Competitions competitions;
  private final Store store;
  private final Onboardings onboardings;
  private final StudyPorts.Objects objects;
  private final Events events;

  public StudyApplicationService(
      Competitions competitions,
      Store store,
      Onboardings onboardings,
      StudyPorts.Objects objects,
      Events events) {
    this.competitions = competitions;
    this.store = store;
    this.onboardings = onboardings;
    this.objects = objects;
    this.events = events;
  }

  public Competition createCompetition(
      UUID workspaceId, String title, String role, String board, LocalDate examDate) {
    var created = competitions.save(Competition.create(workspaceId, title, role, board, examDate));
    var state = onboardings.find(workspaceId);
    if (state.isEmpty()
        || (state.get().competitionId() == null && state.get().completedAt() == null)) {
      Instant now = Instant.now();
      onboardings.save(
          new OnboardingState(
              workspaceId,
              created.id(),
              state.map(OnboardingState::dismissedAt).orElse(null),
              null,
              state.map(OnboardingState::createdAt).orElse(now),
              now));
    }
    return created;
  }

  public OnboardingView onboarding(UUID workspaceId) {
    var saved = onboardings.find(workspaceId);
    if (saved.map(OnboardingState::completedAt).orElse(null) != null) {
      return onboardingView("COMPLETED", "COMPLETED", saved.get(), null);
    }

    Competition selected = selectedCompetition(workspaceId, saved.orElse(null));
    if (selected == null) {
      String status =
          saved.flatMap(it -> Optional.ofNullable(it.dismissedAt())).isPresent()
              ? "DISMISSED"
              : "NOT_STARTED";
      return onboardingView(status, "COMPETITION", saved.orElse(null), null);
    }

    var plan = store.plan(workspaceId, selected.id());
    if (plan.isPresent()) {
      Instant completedAt = plan.get().createdAt();
      var completed =
          new OnboardingState(
              workspaceId,
              selected.id(),
              null,
              completedAt,
              saved.map(OnboardingState::createdAt).orElse(completedAt),
              completedAt);
      onboardings.save(completed);
      return onboardingView("COMPLETED", "COMPLETED", completed, null);
    }

    String step = "DOCUMENT";
    Job processing = null;
    var documents = store.documents(workspaceId, selected.id());
    if (documents.isEmpty()) {
      step = "DOCUMENT";
    } else {
      var syllabus = store.syllabus(workspaceId, selected.id());
      if (syllabus.isPresent()) {
        step = "APPROVED".equals(syllabus.get().status()) ? "AVAILABILITY" : "REVIEW";
      } else {
        var latest = documents.get(0);
        processing = store.latestJob(workspaceId, latest.id(), "SYLLABUS_EXTRACTION").orElse(null);
        step =
            processing != null && "FAILED".equals(processing.status()) ? "DOCUMENT" : "PROCESSING";
      }
    }

    var state =
        saved.orElseGet(
            () -> {
              Instant now = Instant.now();
              return new OnboardingState(workspaceId, selected.id(), null, null, now, now);
            });
    String status = state.dismissedAt() == null ? "IN_PROGRESS" : "DISMISSED";
    return onboardingView(status, step, state, processing);
  }

  public OnboardingView updateOnboarding(UUID workspaceId, boolean dismissed, UUID competitionId) {
    if (competitionId != null) getCompetition(workspaceId, competitionId);
    var current = onboardings.find(workspaceId);
    if (current.map(OnboardingState::completedAt).orElse(null) != null) {
      return onboarding(workspaceId);
    }
    Instant now = Instant.now();
    var saved =
        onboardings.save(
            new OnboardingState(
                workspaceId,
                competitionId != null
                    ? competitionId
                    : current.map(OnboardingState::competitionId).orElse(null),
                dismissed ? now : null,
                null,
                current.map(OnboardingState::createdAt).orElse(now),
                now));
    return onboarding(workspaceId);
  }

  public List<Competition> listCompetitions(UUID workspaceId) {
    return competitions.list(workspaceId);
  }

  public Competition getCompetition(UUID workspaceId, UUID id) {
    return competitions.find(workspaceId, id).orElseThrow(NotFound::new);
  }

  public void deleteCompetition(UUID workspaceId, UUID id) {
    getCompetition(workspaceId, id);
    competitions.delete(workspaceId, id);
  }

  public UploadResult uploadDocument(
      UUID workspaceId, UUID competitionId, String fileName, byte[] content) {
    var competition = getCompetition(workspaceId, competitionId);
    if (content.length == 0 || content.length > 30 * 1024 * 1024)
      throw new IllegalArgumentException("O PDF deve ter entre 1 byte e 30 MB.");
    int version = store.documents(workspaceId, competitionId).size() + 1;
    UUID documentId = UUID.randomUUID();
    String key =
        workspaceId + "/competitions/" + competitionId + "/documents/" + documentId + ".pdf";
    objects.put(key, content, "application/pdf");
    var document =
        new Document(
            documentId,
            workspaceId,
            competitionId,
            version,
            fileName,
            key,
            content.length,
            "EXTRACTING",
            Instant.now());
    store.saveDocument(document);
    competition.processing();
    competitions.save(competition);
    UUID jobId = UUID.randomUUID();
    store.saveJob(
        new Job(
            jobId,
            workspaceId,
            "SYLLABUS_EXTRACTION",
            documentId,
            "QUEUED",
            0,
            null,
            null,
            Instant.now(),
            Instant.now()));
    events.publish(
        "ai.execution.requested",
        jobId,
        Map.of(
            "jobId",
            jobId.toString(),
            "workspaceId",
            workspaceId.toString(),
            "operation",
            "SYLLABUS_EXTRACTION",
            "aggregateId",
            documentId.toString(),
            "objectKey",
            key,
            "fileName",
            fileName,
            "role",
            competition.role(),
            "board",
            competition.board()));
    return new UploadResult(documentId, jobId, "/api/v1/processing-jobs/" + jobId);
  }

  public UploadResult reprocessDocument(UUID workspaceId, UUID competitionId, UUID documentId) {
    getCompetition(workspaceId, competitionId);
    var document = store.document(workspaceId, documentId).orElseThrow(NotFound::new);
    if (!document.competitionId().equals(competitionId)) throw new NotFound();
    UUID jobId = UUID.randomUUID();
    store.saveDocument(
        new Document(
            document.id(),
            document.workspaceId(),
            document.competitionId(),
            document.version(),
            document.fileName(),
            document.objectKey(),
            document.sizeBytes(),
            "EXTRACTING",
            document.createdAt()));
    store.saveJob(
        new Job(
            jobId,
            workspaceId,
            "SYLLABUS_EXTRACTION",
            documentId,
            "QUEUED",
            0,
            null,
            null,
            Instant.now(),
            Instant.now()));
    events.publish(
        "ai.execution.requested",
        jobId,
        Map.of(
            "jobId",
            jobId.toString(),
            "workspaceId",
            workspaceId.toString(),
            "operation",
            "SYLLABUS_EXTRACTION",
            "aggregateId",
            documentId.toString(),
            "objectKey",
            document.objectKey(),
            "fileName",
            document.fileName(),
            "role",
            getCompetition(workspaceId, competitionId).role(),
            "board",
            getCompetition(workspaceId, competitionId).board()));
    return new UploadResult(documentId, jobId, "/api/v1/processing-jobs/" + jobId);
  }

  public List<Document> listDocuments(UUID workspaceId, UUID competitionId) {
    getCompetition(workspaceId, competitionId);
    return store.documents(workspaceId, competitionId);
  }

  public Job job(UUID workspaceId, UUID id) {
    return store.job(workspaceId, id).orElseThrow(NotFound::new);
  }

  public UploadResult uploadMockExam(
      UUID workspaceId, UUID competitionId, String fileName, byte[] content) {
    getCompetition(workspaceId, competitionId);
    if (content.length == 0 || content.length > 30 * 1024 * 1024)
      throw new IllegalArgumentException("O PDF deve ter entre 1 byte e 30 MB.");
    UUID sourceId = UUID.randomUUID();
    String key =
        workspaceId + "/competitions/" + competitionId + "/mock-exams/" + sourceId + ".pdf";
    objects.put(key, content, "application/pdf");
    var now = Instant.now();
    int version = store.mockExamSources(workspaceId, competitionId).size() + 1;
    store.saveMockExamSource(
        new MockExamSource(
            sourceId,
            workspaceId,
            competitionId,
            version,
            fileName,
            key,
            content.length,
            "EXTRACTING",
            null,
            0,
            now,
            now));
    UUID jobId = UUID.randomUUID();
    store.saveJob(
        new Job(
            jobId,
            workspaceId,
            "MOCK_EXAM_EXTRACTION",
            sourceId,
            "QUEUED",
            0,
            null,
            null,
            Instant.now(),
            Instant.now()));
    events.publish(
        "ai.execution.requested",
        jobId,
        Map.of(
            "jobId",
            jobId.toString(),
            "workspaceId",
            workspaceId.toString(),
            "operation",
            "MOCK_EXAM_EXTRACTION",
            "aggregateId",
            sourceId.toString(),
            "competitionId",
            competitionId.toString(),
            "objectKey",
            key,
            "fileName",
            fileName));
    return new UploadResult(sourceId, jobId, "/api/v1/processing-jobs/" + jobId);
  }

  public List<MockExamSource> mockExamSources(UUID workspaceId, UUID competitionId) {
    getCompetition(workspaceId, competitionId);
    return store.mockExamSources(workspaceId, competitionId);
  }

  public List<Question> mockExamQuestions(UUID workspaceId, UUID sourceId) {
    store.mockExamSource(workspaceId, sourceId).orElseThrow(NotFound::new);
    return store.questionsByMockExamSource(workspaceId, sourceId);
  }

  public Question reviewQuestion(UUID workspaceId, UUID questionId, String decision) {
    var question = findQuestion(workspaceId, questionId);
    if (question.mockExamSourceId() == null)
      throw new IllegalArgumentException("Questão não pertence a um simulado enviado.");
    String status =
        switch (decision) {
          case "publish" -> "PUBLISHED";
          case "reject" -> "REJECTED";
          default -> throw new IllegalArgumentException("Decisão inválida.");
        };
    return store.saveQuestion(copyQuestion(question, question.topicId(), status));
  }

  public UploadResult classifyMockExamQuestions(UUID workspaceId, UUID competitionId) {
    var syllabus = syllabus(workspaceId, competitionId);
    var questions =
        store.questions(workspaceId, competitionId).stream()
            .filter(q -> q.mockExamSourceId() != null && "unclassified".equals(q.topicId()))
            .toList();
    UUID jobId = UUID.randomUUID();
    store.saveJob(
        new Job(
            jobId,
            workspaceId,
            "QUESTION_CLASSIFICATION",
            competitionId,
            "QUEUED",
            0,
            null,
            null,
            Instant.now(),
            Instant.now()));
    events.publish(
        "ai.execution.requested",
        jobId,
        Map.of(
            "jobId",
            jobId.toString(),
            "workspaceId",
            workspaceId.toString(),
            "operation",
            "QUESTION_CLASSIFICATION",
            "aggregateId",
            competitionId.toString(),
            "questions",
            questions.stream()
                .map(q -> Map.of("questionId", q.id().toString(), "statement", q.statement()))
                .toList(),
            "topics",
            topics(syllabus.subjects()).stream()
                .map(t -> Map.of("id", t.id(), "name", t.name()))
                .toList()));
    return new UploadResult(competitionId, jobId, "/api/v1/processing-jobs/" + jobId);
  }

  public Syllabus syllabus(UUID workspaceId, UUID competitionId) {
    return store.syllabus(workspaceId, competitionId).orElseThrow(NotFound::new);
  }

  public Syllabus reviseSyllabus(
      UUID workspaceId, UUID competitionId, List<SyllabusSubject> subjects) {
    var old = syllabus(workspaceId, competitionId);
    if ("APPROVED".equals(old.status()))
      throw new IllegalStateException("Uma versão aprovada é imutável.");
    return store.saveSyllabus(
        new Syllabus(
            old.id(),
            workspaceId,
            competitionId,
            old.documentId(),
            old.version(),
            "DRAFT",
            subjects,
            null,
            old.createdAt(),
            Instant.now()));
  }

  public Syllabus approveSyllabus(UUID workspaceId, UUID competitionId) {
    var old = syllabus(workspaceId, competitionId);
    var approved =
        store.saveSyllabus(
            new Syllabus(
                old.id(),
                workspaceId,
                competitionId,
                old.documentId(),
                old.version(),
                "APPROVED",
                old.subjects(),
                Instant.now(),
                old.createdAt(),
                Instant.now()));
    var competition = getCompetition(workspaceId, competitionId);
    if (competition.status() == Competition.Status.REVIEW) competition.activate();
    competitions.save(competition);
    return approved;
  }

  public Plan generatePlan(
      UUID workspaceId, UUID competitionId, LocalDate examDate, List<Availability> availability) {
    var syllabus = syllabus(workspaceId, competitionId);
    if (!"APPROVED".equals(syllabus.status()))
      throw new IllegalStateException("Aprove o edital antes de gerar o plano.");
    if (!examDate.isAfter(LocalDate.now()))
      throw new IllegalArgumentException("A data da prova deve estar no futuro.");

    var rotation = new SubjectRotation(weightedSubjects(syllabus.subjects()));
    List<StudySession> sessions = new ArrayList<>();
    LocalDate date = LocalDate.now();
    while (date.isBefore(examDate) && rotation.hasPending()) {
      int weekday = date.getDayOfWeek().getValue();
      int budget =
          availability.stream()
              .filter(a -> a.weekday() == weekday)
              .mapToInt(Availability::minutes)
              .findFirst()
              .orElse(0);
      if (budget >= MIN_SESSION_MINUTES) {
        int remaining = budget;
        while (remaining >= MIN_SESSION_MINUTES && rotation.hasPending()) {
          Topic topic = rotation.next();
          // Sobra menor que uma sessão mínima é absorvida pela sessão atual, senão o usuário perde
          // até 24 min por dia (90 = 50 + 40, mas 70 = 50 + 20 descartado).
          int size = Math.min(MAX_SESSION_MINUTES, remaining);
          int leftover = remaining - size;
          if (leftover > 0
              && leftover < MIN_SESSION_MINUTES
              && size + leftover <= MAX_SESSION_MINUTES + MIN_SESSION_MINUTES) size = remaining;
          sessions.add(
              new StudySession(
                  UUID.randomUUID(),
                  topic.id(),
                  topic.subject(),
                  topic.name(),
                  date,
                  size,
                  sessions.size() % 4 == 3 ? "review" : "study",
                  StudySession.PLANNED,
                  null,
                  null));
          remaining -= size;
        }
      }
      date = date.plusDays(1);
    }
    // Cobertura: com prova próxima o tempo não comporta todos os tópicos. Antes, o corte era
    // sequencial e comia sempre o fim da lista — justamente Conhecimentos Específicos, que aparece
    // por último no edital. Com a rotação ponderada o que fica de fora é o menos relevante, mas
    // ainda assim nenhuma disciplina pode terminar com zero sessão.
    if (rotation.hasPending())
      addCoveragePass(sessions, rotation, lastStudyableDay(examDate, availability));

    int version = store.plan(workspaceId, competitionId).map(p -> p.version() + 1).orElse(1);
    var now = Instant.now();
    return store.savePlanAndCompleteOnboarding(
        new Plan(
            UUID.randomUUID(),
            workspaceId,
            competitionId,
            version,
            List.copyOf(availability),
            List.copyOf(sessions),
            now,
            now));
  }

  /**
   * Garante ao menos uma sessão por disciplina no último dia útil antes da prova. A carga desse dia
   * fica acima do que o usuário informou, e a tela avisa — mas descartar uma disciplina inteira
   * silenciosamente é pior do que um último dia apertado.
   */
  private void addCoveragePass(
      List<StudySession> sessions, SubjectRotation rotation, LocalDate day) {
    if (day == null) return;
    for (var subject : rotation.subjectsMissingSessions(sessions)) {
      Topic topic = rotation.takeFrom(subject);
      if (topic == null) continue;
      sessions.add(
          new StudySession(
              UUID.randomUUID(),
              topic.id(),
              topic.subject(),
              topic.name(),
              day,
              MIN_SESSION_MINUTES,
              "study",
              StudySession.PLANNED,
              null,
              null));
    }
  }

  private LocalDate lastStudyableDay(LocalDate examDate, List<Availability> availability) {
    LocalDate last = null;
    for (LocalDate day = LocalDate.now(); day.isBefore(examDate); day = day.plusDays(1)) {
      int weekday = day.getDayOfWeek().getValue();
      boolean available =
          availability.stream()
              .anyMatch(a -> a.weekday() == weekday && a.minutes() >= MIN_SESSION_MINUTES);
      if (available) last = day;
    }
    return last;
  }

  /**
   * Disciplinas ordenadas por peso; o peso do edital é a prioridade que a tela promete respeitar.
   */
  private List<SubjectTopics> weightedSubjects(List<SyllabusSubject> subjects) {
    List<SubjectTopics> out = new ArrayList<>();
    for (SyllabusSubject subject : subjects) {
      List<Topic> topics = new ArrayList<>();
      walk(subject.name(), subject.topics(), topics);
      if (topics.isEmpty()) continue;
      // Sort estável: empate de peso mantém a ordem em que o edital lista o tópico.
      topics.sort(Comparator.comparingDouble(Topic::weight).reversed());
      double weight = subject.weight() > 0 ? subject.weight() : 1;
      out.add(new SubjectTopics(subject.name(), weight, topics));
    }
    out.sort(
        Comparator.comparingDouble(SubjectTopics::weight)
            .reversed()
            .thenComparing(SubjectTopics::name));
    return out;
  }

  /**
   * Round-robin ponderado (SWRR): distribui nas proporções do weight e, como todo mundo começa com
   * o próprio peso, o primeiro ciclo já visita todas as disciplinas. É o que impede a
   * Sequência-linear de inanição que existia antes.
   */
  private static final class SubjectRotation {
    private final List<SubjectTopics> subjects;
    private final Map<String, Double> credits = new LinkedHashMap<>();
    private final Map<String, Deque<Topic>> queues = new LinkedHashMap<>();

    SubjectRotation(List<SubjectTopics> subjects) {
      this.subjects = subjects;
      for (SubjectTopics subject : subjects) {
        credits.put(subject.name(), 0d);
        queues.put(subject.name(), new ArrayDeque<>(subject.topics()));
      }
    }

    boolean hasPending() {
      return subjects.stream().anyMatch(s -> !queues.get(s.name()).isEmpty());
    }

    Topic next() {
      while (true) {
        SubjectTargets best = null;
        double bestCredit = Double.NEGATIVE_INFINITY;
        for (SubjectTargets subject : subjects) {
          if (queues.get(subject.name()).isEmpty()) continue;
          double credit = credits.get(subject.name()) + subject.weight();
          if (credit > bestCredit) {
            bestCredit = credit;
            best = subject;
          }
        }
        if (best == null) return null;
        double total = subjects.stream().mapToDouble(SubjectTargets::weight).sum();
        credits.put(best.name(), bestCredit - total);
        return queues.get(best.name()).poll();
      }
    }

    List<String> subjectsMissingSessions(List<StudySession> sessions) {
      var covered = new HashSet<String>();
      for (StudySession session : sessions) covered.add(session.subjectName());
      List<String> missing = new ArrayList<>();
      for (SubjectTargets subject : subjects)
        if (!queues.get(subject.name()).isEmpty() && !covered.contains(subject.name()))
          missing.add(subject.name());
      return missing;
    }

    Topic takeFrom(String name) {
      Deque<Topic> queue = queues.get(name);
      return queue == null || queue.isEmpty() ? null : queue.poll();
    }
  }

  private Competition selectedCompetition(UUID workspaceId, OnboardingState state) {
    if (state != null && state.competitionId() != null) {
      var selected = competitions.find(workspaceId, state.competitionId());
      if (selected.isPresent()) return selected.get();
    }
    return competitions.list(workspaceId).stream()
        .filter(it -> it.status() != Competition.Status.ARCHIVED)
        .findFirst()
        .orElse(null);
  }

  private OnboardingView onboardingView(
      String status, String currentStep, OnboardingState state, Job processingJob) {
    int currentIndex =
        switch (currentStep) {
          case "COMPETITION" -> 0;
          case "DOCUMENT", "PROCESSING" -> 1;
          case "REVIEW" -> 2;
          case "AVAILABILITY" -> 3;
          default -> 4;
        };
    List<String> keys = List.of("COMPETITION", "DOCUMENT", "REVIEW", "AVAILABILITY", "COMPLETED");
    List<String> labels = List.of("Concurso", "Edital", "Conteúdo", "Rotina", "Plano pronto");
    List<OnboardingStep> steps = new ArrayList<>();
    for (int index = 0; index < keys.size(); index++) {
      String stepStatus =
          index < currentIndex ? "DONE" : index == currentIndex ? "CURRENT" : "PENDING";
      steps.add(new OnboardingStep(keys.get(index), labels.get(index), stepStatus));
    }
    return new OnboardingView(
        status,
        currentStep,
        state == null ? null : state.competitionId(),
        state == null ? null : state.dismissedAt(),
        state == null ? null : state.completedAt(),
        List.copyOf(steps),
        processingJob);
  }

  /**
   * Lê o plano reconciliando o que já passou. Uma sessão de ontem que continua "planned" inflava o
   * progresso para sempre e nunca era cobrada nem esquecida; agora ela aparece como "missed" sem
   * precisar de job noturno nem de migração — a reconciliação é derivada do campo date.
   */
  public Plan getPlan(UUID workspaceId, UUID competitionId) {
    return reconcile(
        store.plan(workspaceId, competitionId).orElseThrow(NotFound::new), Instant.now());
  }

  private Plan reconcile(Plan plan, Instant now) {
    var today = LocalDate.now();
    List<StudySession> sessions = new ArrayList<>(plan.sessions().size());
    boolean changed = false;
    for (StudySession session : plan.sessions()) {
      boolean open =
          StudySession.PLANNED.equals(session.status())
              || StudySession.PAUSED.equals(session.status());
      // Correndo não vira missed: o usuário pode ter aberto antes da meia-noite e a sessão ainda
      // está valendo.
      if (open && session.date().isBefore(today)) {
        sessions.add(session.miss());
        changed = true;
      } else {
        sessions.add(session);
      }
    }
    if (!changed) return plan;
    return new Plan(
        plan.id(),
        plan.workspaceId(),
        plan.competitionId(),
        plan.version(),
        plan.availability(),
        List.copyOf(sessions),
        plan.createdAt(),
        plan.updatedAt());
  }

  /**
   * Vence a sessão aberta de uma data que já passou. Correndo não vence: o usuário pode ter aberto
   * antes da meia-noite e a sessão ainda está valendo.
   */
  private static StudySession expire(StudySession session, LocalDate today) {
    if (session.running() || !session.date().isBefore(today)) return session;
    boolean open =
        StudySession.PLANNED.equals(session.status())
            || StudySession.PAUSED.equals(session.status());
    return open ? session.miss() : session;
  }

  private Plan mutateSession(
      UUID workspaceId, UUID competitionId, UUID sessionId, UnaryOperator<StudySession> change) {
    var plan = store.plan(workspaceId, competitionId).orElseThrow(NotFound::new);
    List<StudySession> sessions = new ArrayList<>(plan.sessions());
    boolean found = false;
    for (int i = 0; i < sessions.size(); i++) {
      if (!sessionId.equals(sessions.get(i).id())) continue;
      sessions.set(i, change.apply(sessions.get(i)));
      found = true;
    }
    if (!found) throw new NotFound();
    return store.savePlan(
        new Plan(
            plan.id(),
            workspaceId,
            competitionId,
            plan.version(),
            plan.availability(),
            List.copyOf(sessions),
            plan.createdAt(),
            Instant.now()));
  }

  /**
   * Grava o início da sessão para o pomodoro ter âncora no servidor. Sem isso o cronômetro vive só
   * na memória do navegador e zera a cada F5.
   */
  public Plan startSession(UUID workspaceId, UUID competitionId, UUID sessionId) {
    var started = Instant.now();
    return mutateSession(
        workspaceId,
        competitionId,
        sessionId,
        session -> {
          // Já em curso não reinicia; pausada retoma preservando o tempo investido.
          if (session.running()) return session;
          return session.start(started);
        });
  }

  /**
   * Pausa de verdade: o tempo da execução atual é somado ao acumulado e o startedAt é zerado. Sem
   * isso a pausa era só cosmetics — parava o setInterval, mas ao recarregar a página o tempo
   * continuava correndo a partir do startedAt antigo.
   */
  public Plan pauseSession(UUID workspaceId, UUID competitionId, UUID sessionId) {
    var now = Instant.now();
    return mutateSession(workspaceId, competitionId, sessionId, session -> session.pause(now));
  }

  public Plan completeSession(UUID workspaceId, UUID competitionId, UUID sessionId) {
    var now = Instant.now();
    return mutateSession(workspaceId, competitionId, sessionId, session -> session.complete(now));
  }

  public Flashcard createFlashcard(
      UUID workspaceId, UUID competitionId, String front, String back) {
    var now = Instant.now();
    return store.saveFlashcard(
        new Flashcard(
            UUID.randomUUID(),
            workspaceId,
            competitionId,
            required(front, 600),
            required(back, 2000),
            now,
            now));
  }

  public List<Flashcard> flashcards(UUID workspaceId, UUID competitionId) {
    return store.flashcards(workspaceId, competitionId);
  }

  public void deleteFlashcard(UUID workspaceId, UUID id) {
    store.deleteFlashcard(workspaceId, id);
  }

  public StartSimulationResult startSimulation(
      UUID workspaceId, UUID competitionId, String subjectId, int difficulty, int count) {
    getCompetition(workspaceId, competitionId);
    // O select de disciplina manda string vazia para "Todas as disciplinas". Tratada como id, ela
    // não casa com nenhuma questão e o simulado nunca é montado: a IA gera, o job termina, e a
    // segunda chamada volta a pedir geração. Normaliza aqui, num ponto só.
    String requested = subjectId == null || subjectId.isBlank() ? null : subjectId.trim();
    // Question.topicId guarda o TÓPICO, mas o filtro recebia a DISCIPLINA. Escolher "Matemática"
    // também não achava nada, nem as questões que a IA acabou de gerar para ela.
    Set<String> scope = topicScope(workspaceId, competitionId, requested);
    var available =
        store.questions(workspaceId, competitionId).stream()
            .filter(
                q ->
                    "PUBLISHED".equals(q.status())
                        && (scope == null || scope.contains(q.topicId())))
            .limit(count)
            .toList();
    if (available.size() < count) {
      UUID jobId = UUID.randomUUID();
      store.saveJob(
          new Job(
              jobId,
              workspaceId,
              "QUESTION_GENERATION",
              competitionId,
              "QUEUED",
              0,
              null,
              null,
              Instant.now(),
              Instant.now()));
      events.publish(
          "ai.execution.requested",
          jobId,
          generationPayload(
              workspaceId, competitionId, jobId, requested, difficulty, count - available.size()));
      return new StartSimulationResult(null, List.of(), jobId, "PENDING");
    }
    List<SimulationItem> items =
        available.stream()
            .map(q -> new SimulationItem(q.id(), q.topicId(), q.correctIndex(), null, null))
            .toList();
    var sim =
        new Simulation(
            UUID.randomUUID(),
            workspaceId,
            competitionId,
            null,
            "IN_PROGRESS",
            items,
            0,
            Instant.now(),
            null);
    var saved = store.saveSimulation(sim);
    return new StartSimulationResult(saved, available, null, "READY");
  }

  /**
   * A IA só escreve questão boa se souber sobre o quê. Antes o payload mandava o subjectId cru — um
   * slug sem nome e sem tópicos — então o modelo inventava conteúdo genérico. Aqui vão o nome da
   * disciplina, o cargo, a banca e a lista de tópicos para ela escolher.
   */
  /**
   * Conjunto de topicId que satisfaz o pedido: o próprio id da disciplina mais todos os tópicos
   * descendentes dela. Sem a expansão, uma questão classificada em "geometria-analitica" nunca
   * entraria no simulado de "Matemática" — e a IA, que responde com id de tópico e não de
   * disciplina, produzia exatamente isso.
   */
  private Set<String> topicScope(UUID workspaceId, UUID competitionId, String subjectId) {
    if (subjectId == null) return null;
    Set<String> scope = new LinkedHashSet<>();
    scope.add(subjectId);
    store
        .syllabus(workspaceId, competitionId)
        .ifPresent(
            syllabus ->
                syllabus.subjects().stream()
                    .filter(s -> subjectId.equals(s.id()))
                    .findFirst()
                    .ifPresent(
                        subject -> {
                          List<Topic> collected = new ArrayList<>();
                          walk(subject.name(), subject.topics(), collected);
                          for (Topic topic : collected) scope.add(topic.id());
                        }));
    return scope;
  }

  /**
   * A IA só pode responder com o que conhece. Para uma disciplina ela deve devolver o id da
   * disciplina no topicId; devolver o id de um tópico faria a questão ser filtrada fora da própria
   * disciplina que a originou.
   */
  private Map<String, Object> generationPayload(
      UUID workspaceId,
      UUID competitionId,
      UUID jobId,
      String subjectId,
      int difficulty,
      int count) {
    var competition = getCompetition(workspaceId, competitionId);
    var syllabus = syllabus(workspaceId, competitionId);
    SyllabusSubject subject =
        subjectId == null
            ? null
            : syllabus.subjects().stream()
                .filter(s -> s.id().equals(subjectId))
                .findFirst()
                .orElse(null);
    List<Topic> scope = new ArrayList<>();
    if (subject == null) scope.addAll(topics(syllabus.subjects()));
    else walk(subject.name(), subject.topics(), scope);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("jobId", jobId.toString());
    payload.put("workspaceId", workspaceId.toString());
    payload.put("operation", "QUESTION_GENERATION");
    payload.put("aggregateId", competitionId.toString());
    payload.put("subjectId", subject == null ? "" : subject.id());
    payload.put("subjectName", subject == null ? "" : subject.name());
    payload.put("role", competition.role() == null ? "" : competition.role());
    payload.put("board", competition.board() == null ? "" : competition.board());
    payload.put("difficulty", difficulty);
    payload.put("count", count);
    payload.put("topics", scope.stream().map(t -> Map.of("id", t.id(), "name", t.name())).toList());
    return payload;
  }

  public Simulation answer(UUID workspaceId, UUID id, UUID questionId, int selectedIndex) {
    var sim = requireSimulation(workspaceId, id);
    List<SimulationItem> items = new ArrayList<>(sim.items());
    boolean found = false;
    for (int i = 0; i < items.size(); i++)
      if (questionId.equals(items.get(i).questionId())) {
        items.set(i, items.get(i).answer(selectedIndex, Instant.now()));
        found = true;
      }
    if (!found) throw new NotFound();
    return store.saveSimulation(
        new Simulation(
            sim.id(),
            workspaceId,
            sim.competitionId(),
            sim.title(),
            sim.status(),
            List.copyOf(items),
            sim.durationMinutes(),
            sim.startedAt(),
            sim.finishedAt()));
  }

  public Simulation finish(UUID workspaceId, UUID id) {
    var sim = requireSimulation(workspaceId, id);
    var finished = Instant.now();
    return store.saveSimulation(
        new Simulation(
            sim.id(),
            workspaceId,
            sim.competitionId(),
            sim.title(),
            "FINISHED",
            sim.items(),
            (int) Math.max(1, ChronoUnit.MINUTES.between(sim.startedAt(), finished)),
            sim.startedAt(),
            finished));
  }

  public Simulation simulation(UUID workspaceId, UUID id) {
    return requireSimulation(workspaceId, id);
  }

  public List<Question> simulationQuestions(UUID workspaceId, UUID id) {
    var simulation = requireSimulation(workspaceId, id);
    Set<UUID> ids =
        simulation.items().stream()
            .map(SimulationItem::questionId)
            .collect(java.util.stream.Collectors.toSet());
    return store.questions(workspaceId, simulation.competitionId()).stream()
        .filter(q -> ids.contains(q.id()))
        .toList();
  }

  public Simulation renameSimulation(UUID workspaceId, UUID id, String title) {
    var current = requireSimulation(workspaceId, id);
    return store.saveSimulation(
        new Simulation(
            current.id(),
            current.workspaceId(),
            current.competitionId(),
            required(title, 160),
            current.status(),
            current.items(),
            current.durationMinutes(),
            current.startedAt(),
            current.finishedAt()));
  }

  public List<Simulation> simulations(UUID workspaceId, UUID competitionId) {
    return store.simulations(workspaceId, competitionId);
  }

  public Progress progress(UUID workspaceId) {
    var allPlans =
        competitions.list(workspaceId).stream()
            .map(c -> store.plan(workspaceId, c.id()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    var sims = store.simulations(workspaceId, null);
    var now = Instant.now();
    var today = LocalDate.now();
    // Mesma reconciliação do getPlan: sem isso a sessão vencida contaria como pendente para sempre
    // e o progresso jamais fecharia.
    var sessions =
        allPlans.stream().flatMap(p -> p.sessions().stream()).map(s -> expire(s, today)).toList();
    int completed =
        (int) sessions.stream().filter(s -> StudySession.COMPLETED.equals(s.status())).count();
    // Sessão vencida vira "missed" na leitura do plano. Ela não conta como concluída nem entra em
    // nenhum denominador, senão o progresso nunca chega a 100% e o usuário não descobre o que
    // falta.
    int missed = (int) sessions.stream().filter(StudySession::missed).count();
    // Tempo de foco de verdade: acumulado das sessões fechadas + corrida das que estão em aberto.
    long focusedSeconds =
        sessions.stream()
            .filter(
                s ->
                    StudySession.COMPLETED.equals(s.status())
                        || s.running()
                        || StudySession.PAUSED.equals(s.status()))
            .mapToLong(s -> s.investedSeconds(now))
            .sum();
    int answered = 0, correct = 0;
    Set<LocalDate> active = new HashSet<>();
    for (var s : sims) {
      for (SimulationItem item : s.items()) {
        if (item.selectedIndex() != null) {
          answered++;
          if (item.selectedIndex() == item.correctIndex()) correct++;
        }
      }
      active.add(s.startedAt().atZone(ZoneOffset.UTC).toLocalDate());
    }
    int streak = 0;
    for (LocalDate day = LocalDate.now(); active.contains(day); day = day.minusDays(1)) streak++;
    int xp =
        completed * 10
            + correct * 5
            + (int) sims.stream().filter(s -> "FINISHED".equals(s.status())).count() * 25;
    return new Progress(
        xp,
        xp / 300 + 1,
        streak,
        completed,
        answered,
        answered == 0 ? 0 : (double) correct / answered,
        missed,
        focusedSeconds / 60);
  }

  public void applyAiResult(
      UUID jobId, String status, AiResult result, String errorCode, String errorMessage) {
    var job = store.job(findJobWorkspace(jobId), jobId).orElseThrow(NotFound::new);
    int progress = "COMPLETED".equals(status) ? 100 : 0;
    store.saveJob(
        new Job(
            job.id(),
            job.workspaceId(),
            job.type(),
            job.aggregateId(),
            status,
            progress,
            errorCode,
            errorMessage,
            job.createdAt(),
            Instant.now()));
    if (!"COMPLETED".equals(status)) return;
    if ("SYLLABUS_EXTRACTION".equals(job.type())) {
      var doc = store.document(job.workspaceId(), job.aggregateId()).orElseThrow();
      var existing = store.syllabus(job.workspaceId(), doc.competitionId());
      int version = existing.map(s -> s.version() + 1).orElse(1);
      var now = Instant.now();
      store.saveSyllabus(
          new Syllabus(
              UUID.randomUUID(),
              job.workspaceId(),
              doc.competitionId(),
              doc.id(),
              version,
              "DRAFT",
              result.subjects(),
              null,
              now,
              now));
      var competition = getCompetition(job.workspaceId(), doc.competitionId());
      competition.review();
      competitions.save(competition);
    } else if ("QUESTION_CLASSIFICATION".equals(job.type())) {
      for (Classification assignment : result.assignments()) {
        try {
          var question = findQuestion(job.workspaceId(), assignment.questionId());
          store.saveQuestion(copyQuestion(question, assignment.topicId(), question.status()));
        } catch (NotFound ignored) {
          // Ignore assignments that do not belong to the requesting workspace.
        }
      }
    } else if ("QUESTION_GENERATION".equals(job.type())
        || "MOCK_EXAM_EXTRACTION".equals(job.type())) {
      String origin = "QUESTION_GENERATION".equals(job.type()) ? "AI_GENERATED" : "LICENSED";
      MockExamSource source =
          "MOCK_EXAM_EXTRACTION".equals(job.type())
              ? store
                  .mockExamSource(job.workspaceId(), job.aggregateId())
                  .orElseThrow(NotFound::new)
              : null;
      UUID competitionId = source == null ? job.aggregateId() : source.competitionId();
      // Questão extraída de PDF de banca entra como rascunho: é material de terceiros e o usuário
      // precisa aprovar. Questão gerada para este usuário já vai publicada — antes ela nascia
      // DRAFT e nada no produto a publicava, então o simulado com IA nunca tinha com o que montar.
      String questionStatus = source == null ? "PUBLISHED" : "DRAFT";
      for (GeneratedQuestion q : result.questions()) {
        var now = Instant.now();
        store.saveQuestion(
            new Question(
                UUID.randomUUID(),
                job.workspaceId(),
                competitionId,
                source == null ? null : source.id(),
                q.topicId() == null || q.topicId().isBlank() ? "unclassified" : q.topicId(),
                origin,
                questionStatus,
                q.boardStyle(),
                q.difficulty() == 0 ? 3 : q.difficulty(),
                q.statement(),
                q.alternatives(),
                q.correctIndex(),
                q.explanation(),
                List.of(),
                now,
                now));
      }
      if (source != null) {
        store.saveMockExamSource(
            new MockExamSource(
                source.id(),
                source.workspaceId(),
                source.competitionId(),
                source.version(),
                source.fileName(),
                source.objectKey(),
                source.sizeBytes(),
                "COMPLETED",
                result.board(),
                result.questions().size(),
                source.createdAt(),
                Instant.now()));
      }
    }
  }

  private Question findQuestion(UUID workspaceId, UUID id) {
    return store.questions(workspaceId, null).stream()
        .filter(q -> q.id().equals(id))
        .findFirst()
        .orElseThrow(NotFound::new);
  }

  private static Question copyQuestion(Question q, String topicId, String status) {
    return new Question(
        q.id(),
        q.workspaceId(),
        q.competitionId(),
        q.mockExamSourceId(),
        topicId,
        q.origin(),
        status,
        q.boardStyle(),
        q.difficulty(),
        q.statement(),
        q.alternatives(),
        q.correctIndex(),
        q.explanation(),
        q.sources(),
        q.createdAt(),
        Instant.now());
  }

  private UUID findJobWorkspace(UUID jobId) {
    return store instanceof JobWorkspaceLookup lookup
        ? lookup.workspaceForJob(jobId).orElseThrow(NotFound::new)
        : UUID.fromString("00000000-0000-0000-0000-000000000000");
  }

  private Simulation requireSimulation(UUID workspaceId, UUID id) {
    return store.simulation(workspaceId, id).orElseThrow(NotFound::new);
  }

  private static String required(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException("Conteúdo inválido.");
    return value.trim();
  }

  private List<Topic> topics(List<SyllabusSubject> subjects) {
    List<Topic> out = new ArrayList<>();
    for (SyllabusSubject subject : subjects) walk(subject.name(), subject.topics(), out);
    return out;
  }

  private void walk(String subject, List<SyllabusTopic> nodes, List<Topic> out) {
    for (SyllabusTopic node : nodes) {
      out.add(
          new Topic(
              node.id() == null || node.id().isBlank() ? UUID.randomUUID().toString() : node.id(),
              subject,
              node.name() == null || node.name().isBlank() ? "Tópico" : node.name(),
              node.weight() > 0 ? node.weight() : 1));
      walk(subject, node.children(), out);
    }
  }

  public interface JobWorkspaceLookup {
    Optional<UUID> workspaceForJob(UUID id);
  }

  private record Topic(String id, String subject, String name, double weight) {}

  private record SubjectTopics(String name, double weight, List<Topic> topics)
      implements SubjectTargets {}

  private interface SubjectTargets {
    String name();

    double weight();
  }

  public record OnboardingStep(String key, String label, String status) {}

  public record OnboardingView(
      String status,
      String currentStep,
      UUID competitionId,
      Instant dismissedAt,
      Instant completedAt,
      List<OnboardingStep> steps,
      Job processingJob) {}

  public record UploadResult(UUID documentId, UUID jobId, String statusUrl) {}

  public record StartSimulationResult(
      Simulation simulation, List<Question> questions, UUID jobId, String status) {}

  public static class NotFound extends RuntimeException {
    public NotFound() {
      super("Recurso não encontrado.");
    }
  }
}
