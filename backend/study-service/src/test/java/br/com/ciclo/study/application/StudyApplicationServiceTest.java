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
        new Syllabus(
            UUID.randomUUID(),
            workspaceId,
            competitionId,
            UUID.randomUUID(),
            1,
            "APPROVED",
            List.of(
                new SyllabusSubject(
                    "law",
                    "Direito",
                    1,
                    List.of(
                        new SyllabusTopic("constitutional", "Constitucional", 1, null, List.of()),
                        new SyllabusTopic(
                            "administrative", "Administrativo", 1, null, List.of())))),
            Instant.now(),
            Instant.now(),
            Instant.now());
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
            "completed");
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
