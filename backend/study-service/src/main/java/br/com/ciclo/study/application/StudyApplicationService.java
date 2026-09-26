package br.com.ciclo.study.application;

import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class StudyApplicationService {
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
    List<StudySession> sessions = new ArrayList<>();
    List<Topic> topics = topics(syllabus.subjects());
    int topicIndex = 0;
    LocalDate date = LocalDate.now();
    while (date.isBefore(examDate) && topicIndex < topics.size()) {
      int weekday = date.getDayOfWeek().getValue();
      int minutes =
          availability.stream()
              .filter(a -> a.weekday() == weekday)
              .mapToInt(Availability::minutes)
              .findFirst()
              .orElse(0);
      if (minutes >= 25) {
        int remaining = minutes;
        while (remaining >= 25 && topicIndex < topics.size()) {
          var topic = topics.get(topicIndex++);
          sessions.add(
              new StudySession(
                  UUID.randomUUID(),
                  topic.id(),
                  topic.subject(),
                  topic.name(),
                  date,
                  Math.min(50, remaining),
                  topicIndex % 4 == 0 ? "review" : "study",
                  "planned"));
          remaining -= Math.min(50, remaining);
        }
      }
      date = date.plusDays(1);
    }
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

  public Plan getPlan(UUID workspaceId, UUID competitionId) {
    return store.plan(workspaceId, competitionId).orElseThrow(NotFound::new);
  }

  public Plan completeSession(UUID workspaceId, UUID competitionId, UUID sessionId) {
    var plan = getPlan(workspaceId, competitionId);
    List<StudySession> sessions = new ArrayList<>(plan.sessions());
    boolean found = false;
    for (int i = 0; i < sessions.size(); i++) {
      if (sessionId.equals(sessions.get(i).id())) {
        sessions.set(i, sessions.get(i).complete());
        found = true;
      }
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
    var available =
        store.questions(workspaceId, competitionId).stream()
            .filter(
                q ->
                    "PUBLISHED".equals(q.status())
                        && (subjectId == null || subjectId.equals(q.topicId())))
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
          Map.of(
              "jobId",
              jobId.toString(),
              "workspaceId",
              workspaceId.toString(),
              "operation",
              "QUESTION_GENERATION",
              "aggregateId",
              competitionId.toString(),
              "subjectId",
              subjectId == null ? "" : subjectId,
              "difficulty",
              difficulty,
              "count",
              count - available.size()));
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
    int completed =
        allPlans.stream()
            .mapToInt(
                p -> {
                  int n = 0;
                  for (StudySession s : p.sessions()) if ("completed".equals(s.status())) n++;
                  return n;
                })
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
        answered == 0 ? 0 : (double) correct / answered);
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
                "DRAFT",
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
              node.name() == null || node.name().isBlank() ? "Tópico" : node.name()));
      walk(subject, node.children(), out);
    }
  }

  public interface JobWorkspaceLookup {
    Optional<UUID> workspaceForJob(UUID id);
  }

  private record Topic(String id, String subject, String name) {}

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
