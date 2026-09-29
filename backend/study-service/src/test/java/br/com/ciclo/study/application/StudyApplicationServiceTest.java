package br.com.ciclo.study.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StudyApplicationServiceTest {
  @Mock Competitions competitions;
  @Mock Store store;
  @Mock Onboardings onboardings;
  @Mock StudyPorts.Objects objects;
  @Mock Events events;

  @Test
  void plansTypedSessionsFromApprovedSyllabus() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var tomorrow = LocalDate.now().plusDays(1);
    var syllabus =
        approvedSyllabus(
            workspaceId,
            competitionId,
            List.of(
                new SyllabusSubject(
                    "law",
                    "Direito",
                    1,
                    List.of(
                        new SyllabusTopic("constitutional", "Constitucional", 1, null, List.of()),
                        new SyllabusTopic(
                            "administrative", "Administrativo", 1, null, List.of())))));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.plan(workspaceId, competitionId)).thenReturn(Optional.empty());
    when(store.savePlanAndCompleteOnboarding(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var plan =
        service.generatePlan(
            workspaceId,
            competitionId,
            tomorrow.plusDays(2),
            List.of(new Availability(tomorrow.getDayOfWeek().getValue(), 100)));

    assertThat(plan.version()).isOne();
    assertThat(plan.sessions())
        .extracting(StudySession::topicId)
        .containsExactly("constitutional", "administrative");
    assertThat(plan.sessions()).allMatch(session -> session.date().equals(tomorrow));
    // 100 min cabem em duas sessões de 50.
    assertThat(plan.sessions().stream().mapToInt(StudySession::minutes).sum()).isEqualTo(100);
  }

  /**
   * O bug reportado: o plano era uma lista sequencial e o corte por data da prova comia sempre o
   * fim da lista — justamente Conhecimentos Específicos, que aparece por último no edital. Agora
   * toda disciplina do edital entra no plano.
   */
  @Test
  void everySubjectFromTheSyllabusGetsSessionsEvenWhenTheExamIsClose() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var tomorrow = LocalDate.now().plusDays(1);
    var syllabus = approvedSyllabus(workspaceId, competitionId, subjectsOf(1, 1, 1, 1, 1));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.plan(workspaceId, competitionId)).thenReturn(Optional.empty());
    when(store.savePlanAndCompleteOnboarding(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // Só um dia de estudo para cinco disciplinas: o teste é se nenhuma some.
    var plan =
        service.generatePlan(
            workspaceId,
            competitionId,
            tomorrow.plusDays(1),
            List.of(new Availability(tomorrow.getDayOfWeek().getValue(), 50)));

    assertThat(plan.sessions())
        .extracting(StudySession::subjectName)
        .contains("Disciplina 1", "Disciplina 2", "Disciplina 3", "Disciplina 4", "Disciplina 5");
  }

  @Test
  void heavierSubjectsGetMoreSessions() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var tomorrow = LocalDate.now().plusDays(1);
    var syllabus =
        approvedSyllabus(
            workspaceId,
            competitionId,
            List.of(
                new SyllabusSubject(
                    "leve", "Leve", 1, List.of(new SyllabusTopic("l1", "L1", 1, null, List.of()))),
                new SyllabusSubject(
                    "pesado",
                    "Pesado",
                    4,
                    List.of(
                        new SyllabusTopic("p1", "P1", 1, null, List.of()),
                        new SyllabusTopic("p2", "P2", 1, null, List.of()),
                        new SyllabusTopic("p3", "P3", 1, null, List.of()),
                        new SyllabusTopic("p4", "P4", 1, null, List.of())))));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.plan(workspaceId, competitionId)).thenReturn(Optional.empty());
    when(store.savePlanAndCompleteOnboarding(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // Disponibilidade nos sete dias: com um único dia útil não há como comparar proporções.
    var everyDay = new java.util.ArrayList<Availability>();
    for (int weekday = 1; weekday <= 7; weekday++) everyDay.add(new Availability(weekday, 50));

    var plan = service.generatePlan(workspaceId, competitionId, tomorrow.plusDays(4), everyDay);

    long heavy = plan.sessions().stream().filter(s -> s.subjectName().equals("Pesado")).count();
    long light = plan.sessions().stream().filter(s -> s.subjectName().equals("Leve")).count();
    assertThat(heavy).isGreaterThan(light);
  }

  @Test
  void interleavesSubjectsInsteadOfDrainingOneBeforeTheNext() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var tomorrow = LocalDate.now().plusDays(1);
    var syllabus = approvedSyllabus(workspaceId, competitionId, subjectsOf(1, 1, 1));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.plan(workspaceId, competitionId)).thenReturn(Optional.empty());
    when(store.savePlanAndCompleteOnboarding(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var plan =
        service.generatePlan(
            workspaceId,
            competitionId,
            tomorrow.plusDays(2),
            List.of(new Availability(tomorrow.getDayOfWeek().getValue(), 100)));

    assertThat(plan.sessions().subList(0, 3))
        .extracting(StudySession::subjectName)
        .containsExactlyInAnyOrder("Disciplina 1", "Disciplina 2", "Disciplina 3");
  }

  @Test
  void absorbsTheRemainderInsteadOfDiscardingIt() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var tomorrow = LocalDate.now().plusDays(1);
    // 70 min: antes virava 50 + 20 (20 jogados fora). Agora uma sessão de 70.
    var syllabus =
        approvedSyllabus(
            workspaceId,
            competitionId,
            List.of(
                new SyllabusSubject(
                    "unica",
                    "Única",
                    1,
                    List.of(new SyllabusTopic("t1", "T1", 1, null, List.of())))));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.plan(workspaceId, competitionId)).thenReturn(Optional.empty());
    when(store.savePlanAndCompleteOnboarding(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var plan =
        service.generatePlan(
            workspaceId,
            competitionId,
            tomorrow.plusDays(1),
            List.of(new Availability(tomorrow.getDayOfWeek().getValue(), 70)));

    assertThat(plan.sessions().stream().mapToInt(StudySession::minutes).sum()).isEqualTo(70);
  }

  @Test
  void recordsTheStartInstantSoThePomodoroSurvivesAReload() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var session =
        new StudySession(
            UUID.randomUUID(), "t1", "S", "T1", LocalDate.now(), 50, "study", "planned", null);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(
            Optional.of(
                new Plan(
                    UUID.randomUUID(),
                    workspaceId,
                    competitionId,
                    1,
                    List.of(),
                    List.of(session),
                    Instant.now(),
                    Instant.now())));
    when(store.savePlan(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var plan = service.startSession(workspaceId, competitionId, session.id());

    assertThat(plan.sessions().get(0).startedAt()).isNotNull();
  }

  @Test
  void reenteringASessionDoesNotResetTheTimeAlreadySpent() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var started = Instant.now().minusSeconds(600);
    var session =
        new StudySession(
            UUID.randomUUID(), "t1", "S", "T1", LocalDate.now(), 50, "study", "planned", started);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(
            Optional.of(
                new Plan(
                    UUID.randomUUID(),
                    workspaceId,
                    competitionId,
                    1,
                    List.of(),
                    List.of(session),
                    Instant.now(),
                    Instant.now())));
    when(store.savePlan(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var plan = service.startSession(workspaceId, competitionId, session.id());

    assertThat(plan.sessions().get(0).startedAt()).isEqualTo(started);
  }

  private static List<SyllabusSubject> subjectsOf(double... weights) {
    var out = new java.util.ArrayList<SyllabusSubject>();
    for (int i = 0; i < weights.length; i++)
      out.add(
          new SyllabusSubject(
              "d" + (i + 1),
              "Disciplina " + (i + 1),
              weights[i],
              List.of(new SyllabusTopic("t" + (i + 1), "Tópico " + (i + 1), 1, null, List.of()))));
    return out;
  }

  private static Syllabus approvedSyllabus(
      UUID workspaceId, UUID competitionId, List<SyllabusSubject> subjects) {
    return new Syllabus(
        UUID.randomUUID(),
        workspaceId,
        competitionId,
        UUID.randomUUID(),
        1,
        "APPROVED",
        subjects,
        Instant.now(),
        Instant.now(),
        Instant.now());
  }

  @Test
  void startsOnboardingAtCompetitionWhenWorkspaceIsEmpty() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    when(onboardings.find(workspaceId)).thenReturn(Optional.empty());
    when(competitions.list(workspaceId)).thenReturn(List.of());

    var onboarding = service.onboarding(workspaceId);

    assertThat(onboarding.status()).isEqualTo("NOT_STARTED");
    assertThat(onboarding.currentStep()).isEqualTo("COMPETITION");
    assertThat(onboarding.steps())
        .extracting(StudyApplicationService.OnboardingStep::status)
        .containsExactly("CURRENT", "PENDING", "PENDING", "PENDING", "PENDING");
  }

  @Test
  void resumesAtReviewWhenSyllabusWasExtracted() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competition =
        Competition.create(
            workspaceId, "Concurso", "Analista", "Banca", LocalDate.now().plusDays(30));
    var now = Instant.now();
    var state = new OnboardingState(workspaceId, competition.id(), now, null, now, now);
    var document =
        new Document(
            UUID.randomUUID(),
            workspaceId,
            competition.id(),
            1,
            "edital.pdf",
            "document.pdf",
            100,
            "NEEDS_REVIEW",
            now);
    var syllabus =
        new Syllabus(
            UUID.randomUUID(),
            workspaceId,
            competition.id(),
            document.id(),
            1,
            "DRAFT",
            List.of(),
            null,
            now,
            now);
    when(onboardings.find(workspaceId)).thenReturn(Optional.of(state));
    when(competitions.find(workspaceId, competition.id())).thenReturn(Optional.of(competition));
    when(store.plan(workspaceId, competition.id())).thenReturn(Optional.empty());
    when(store.documents(workspaceId, competition.id())).thenReturn(List.of(document));
    when(store.syllabus(workspaceId, competition.id())).thenReturn(Optional.of(syllabus));

    var onboarding = service.onboarding(workspaceId);

    assertThat(onboarding.status()).isEqualTo("DISMISSED");
    assertThat(onboarding.currentStep()).isEqualTo("REVIEW");
  }

  @Test
  void refusesToAssociateCompetitionFromAnotherWorkspace() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var foreignCompetitionId = UUID.randomUUID();
    when(competitions.find(workspaceId, foreignCompetitionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateOnboarding(workspaceId, false, foreignCompetitionId))
        .isInstanceOf(StudyApplicationService.NotFound.class);
  }

  @Test
  void recordsAnAnswerWithoutMutatingTheOriginalSimulation() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var simulationId = UUID.randomUUID();
    var questionId = UUID.randomUUID();
    var original =
        new Simulation(
            simulationId,
            workspaceId,
            UUID.randomUUID(),
            "Simulado",
            "IN_PROGRESS",
            List.of(new SimulationItem(questionId, "law", 2, null, null)),
            0,
            Instant.now(),
            null);
    when(store.simulation(workspaceId, simulationId)).thenReturn(Optional.of(original));
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var answered = service.answer(workspaceId, simulationId, questionId, 2);

    assertThat(original.items().get(0).selectedIndex()).isNull();
    assertThat(answered.items().get(0).selectedIndex()).isEqualTo(2);
    assertThat(answered.items().get(0).answeredAt()).isNotNull();
  }

  @Test
  void calculatesProgressFromCompletedSessionsAndSimulationAnswers() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competition =
        Competition.create(
            workspaceId, "Concurso", "Analista", "Banca", LocalDate.now().plusDays(30));
    var session =
        new StudySession(
            UUID.randomUUID(),
            "law",
            "Direito",
            "Constitucional",
            LocalDate.now(),
            50,
            "study",
            "completed",
            null);
    var plan =
        new Plan(
            UUID.randomUUID(),
            workspaceId,
            competition.id(),
            1,
            List.of(),
            List.of(session),
            Instant.now(),
            Instant.now());
    var simulation =
        new Simulation(
            UUID.randomUUID(),
            workspaceId,
            competition.id(),
            "Simulado",
            "FINISHED",
            List.of(
                new SimulationItem(UUID.randomUUID(), "law", 1, 1, Instant.now()),
                new SimulationItem(UUID.randomUUID(), "law", 2, 0, Instant.now())),
            10,
            Instant.now(),
            Instant.now());
    when(competitions.list(workspaceId)).thenReturn(List.of(competition));
    when(store.plan(workspaceId, competition.id())).thenReturn(Optional.of(plan));
    when(store.simulations(workspaceId, null)).thenReturn(List.of(simulation));

    var progress = service.progress(workspaceId);

    assertThat(progress.completedSessions()).isOne();
    assertThat(progress.totalAnswered()).isEqualTo(2);
    assertThat(progress.averageAccuracy()).isEqualTo(0.5);
    assertThat(progress.xp()).isEqualTo(40);
  }

  @Test
  void renamesSimulationWithoutChangingItsRunState() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var simulationId = UUID.randomUUID();
    var original =
        new Simulation(
            simulationId,
            workspaceId,
            UUID.randomUUID(),
            null,
            "IN_PROGRESS",
            List.of(),
            15,
            Instant.now(),
            null);
    when(store.simulation(workspaceId, simulationId)).thenReturn(Optional.of(original));
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var renamed = service.renameSimulation(workspaceId, simulationId, "Revisão de direito");

    assertThat(renamed.title()).isEqualTo("Revisão de direito");
    assertThat(renamed.status()).isEqualTo(original.status());
    assertThat(renamed.startedAt()).isEqualTo(original.startedAt());
  }

  @Test
  void publishesQuestionFromOwnedMockExam() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var questionId = UUID.randomUUID();
    var question =
        new Question(
            questionId,
            workspaceId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "unclassified",
            "LICENSED",
            "DRAFT",
            "Cebraspe",
            3,
            "Enunciado",
            List.of("A", "B"),
            0,
            "Explicação",
            List.of(),
            Instant.now(),
            Instant.now());
    when(store.questions(workspaceId, null)).thenReturn(List.of(question));
    when(store.saveQuestion(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var reviewed = service.reviewQuestion(workspaceId, questionId, "publish");

    assertThat(reviewed.status()).isEqualTo("PUBLISHED");
    assertThat(reviewed.mockExamSourceId()).isEqualTo(question.mockExamSourceId());
  }

  private StudyApplicationService service() {
    return new StudyApplicationService(competitions, store, onboardings, objects, events);
  }
}
