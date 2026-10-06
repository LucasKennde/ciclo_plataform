package br.com.ciclo.study.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

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
            UUID.randomUUID(), "t1", "S", "T1", LocalDate.now(), 50, "study", "planned", null, 0);
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
            UUID.randomUUID(),
            "t1",
            "S",
            "T1",
            LocalDate.now(),
            50,
            "study",
            "planned",
            started,
            0);
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

  @Test
  void pausingBanksTheElapsedTimeSoItSurvivesAReload() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var id = UUID.randomUUID();
    var running =
        new StudySession(
            id, "t1", "S", "T1", LocalDate.now(), 50, "study", "in_progress", Instant.now(), 0);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(Optional.of(planWith(workspaceId, competitionId, running)));
    when(store.savePlan(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var plan = service.pauseSession(workspaceId, competitionId, id);

    var session = plan.sessions().get(0);
    assertThat(session.status()).isEqualTo("paused");
    // Pausar tem que zerar o startedAt: se ele sobreviver, o relógio volta a correr do zero quando
    // a página recarrega e a pausa vira mentira.
    assertThat(session.startedAt()).isNull();
    assertThat(session.investedSeconds(Instant.now())).isGreaterThanOrEqualTo(0);
  }

  @Test
  void resumingAPausedSessionKeepsTheBankedTime() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var id = UUID.randomUUID();
    var paused =
        new StudySession(id, "t1", "S", "T1", LocalDate.now(), 50, "study", "paused", null, 900);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(Optional.of(planWith(workspaceId, competitionId, paused)));
    when(store.savePlan(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var plan = service.startSession(workspaceId, competitionId, id);

    var session = plan.sessions().get(0);
    assertThat(session.status()).isEqualTo("in_progress");
    // 15 min de work anterior não podem ser devolvidos ao retomar.
    assertThat(session.accumulatedSeconds()).isEqualTo(900);
    assertThat(session.remainingSeconds(Instant.now())).isLessThan(50L * 60);
  }

  @Test
  void sessionsFromPastDatesBecomeMissedSoTheyStopInflatingProgress() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var yesterday = LocalDate.now().minusDays(1);
    var stale =
        new StudySession(
            UUID.randomUUID(), "t1", "S", "T1", yesterday, 50, "study", "planned", null, 0);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(Optional.of(planWith(workspaceId, competitionId, stale)));

    var plan = service.getPlan(workspaceId, competitionId);

    assertThat(plan.sessions().get(0).status()).isEqualTo("missed");
  }

  @Test
  void aRunningSessionThatCrossedMidnightIsNotMarkedMissed() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    var yesterday = LocalDate.now().minusDays(1);
    var running =
        new StudySession(
            UUID.randomUUID(),
            "t1",
            "S",
            "T1",
            yesterday,
            50,
            "study",
            "in_progress",
            Instant.now(),
            0);
    when(store.plan(workspaceId, competitionId))
        .thenReturn(Optional.of(planWith(workspaceId, competitionId, running)));

    var plan = service.getPlan(workspaceId, competitionId);

    // O usuário abriu a sessão antes da meia-noite; matar isso é errar a favor do sistema.
    assertThat(plan.sessions().get(0).status()).isEqualTo("in_progress");
  }

  @Test
  void progressCountsOnlyScheduledSessionsAndReportsTheMissedOnes() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var yesterday = LocalDate.now().minusDays(1);
    var done =
        new StudySession(
            UUID.randomUUID(),
            "t1",
            "S",
            "T1",
            LocalDate.now(),
            50,
            "study",
            "completed",
            null,
            3000);
    var stale =
        new StudySession(
            UUID.randomUUID(), "t2", "S", "T2", yesterday, 50, "study", "planned", null, 0);
    // competition.id() e não um id solto: o store é consultado pelo id que o create gerou.
    var competition = competition(workspaceId);
    var competitionId = competition.id();
    when(competitions.list(workspaceId)).thenReturn(List.of(competition));
    when(store.plan(workspaceId, competitionId))
        .thenReturn(Optional.of(planWith(workspaceId, competitionId, done, stale)));

    var progress = service.progress(workspaceId);

    assertThat(progress.completedSessions()).isEqualTo(1);
    assertThat(progress.missedSessions()).isEqualTo(1);
    assertThat(progress.focusedMinutes()).isEqualTo(50);
  }

  /**
   * startSimulation exige o concurso existir. competition() gera um id próprio, então o stub casa
   * por qualquer id em vez de tentar adivinhar o do teste.
   */
  private void withCompetition(UUID workspaceId) {
    when(competitions.find(any(), any())).thenReturn(Optional.of(competition(workspaceId)));
  }

  private static Competition competition(UUID workspaceId) {
    return Competition.create(workspaceId, "C", "R", "B", LocalDate.now().plusDays(30));
  }

  private static Plan planWith(UUID workspaceId, UUID competitionId, StudySession... sessions) {
    return new Plan(
        UUID.randomUUID(),
        workspaceId,
        competitionId,
        1,
        List.of(),
        List.of(sessions),
        Instant.now(),
        Instant.now());
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

  @Test
  void anEmptySubjectIdMeansEverySubjectAndMustNotFilterEverythingOut() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    // O <select> do front manda string vazia para "Todas as disciplinas". Tratada como id, ela não
    // casa com nada e o simulado nunca sai.
    when(store.questions(workspaceId, competitionId))
        .thenReturn(
            List.of(
                question(competitionId, "PUBLISHED", "port"),
                question(competitionId, "PUBLISHED", "mat")));
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.startSimulation(workspaceId, competitionId, "", 3, 2);

    assertThat(result.status()).isEqualTo("READY");
    assertThat(result.simulation()).isNotNull();
    assertThat(result.questions()).hasSize(2);
  }

  @Test
  void questionsTaggedWithADescendantTopicOfTheChosenSubjectCount() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    var plan = new SyllabusTopic("plano", "Plano", 1, null, List.of());
    var geometria = new SyllabusTopic("geometria", "Geometria", 1, null, List.of(plan));
    var matematica = new SyllabusSubject("mat", "Matemática", 1, List.of(geometria));
    var syllabus = approvedSyllabus(workspaceId, competitionId, List.of(matematica));
    when(store.syllabus(workspaceId, competitionId)).thenReturn(Optional.of(syllabus));
    when(store.questions(workspaceId, competitionId))
        .thenReturn(List.of(question(competitionId, "PUBLISHED", "plano")));
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    // topicId é do tópico descendente, não da disciplina. Sem expandir a árvore, não casaria.
    var result = service.startSimulation(workspaceId, competitionId, "mat", 3, 1);

    assertThat(result.status()).isEqualTo("READY");
    assertThat(result.simulation()).isNotNull();
  }

  @Test
  void theSecondCallAfterGenerationActuallyBuildsTheSimulation() {
    // Este é o fluxo real do bug: banco vazio -> gera -> o job termina -> o front chama de novo e
    // precisa receber o simulado. Com o filtro errado, a segunda chamada voltava a pedir geração.
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    // Cai no ramo de geração, e generationPayload precisa do edital montado.
    when(store.syllabus(workspaceId, competitionId))
        .thenReturn(Optional.of(approvedSyllabus(workspaceId, competitionId, subjectsOf(1))));
    when(store.questions(workspaceId, competitionId)).thenReturn(List.of());
    when(store.saveJob(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var first = service.startSimulation(workspaceId, competitionId, "", 3, 5);

    assertThat(first.status()).isEqualTo("PENDING");
    assertThat(first.simulation()).isNull();

    // A IA terminou: as questões agora estão publicadas no banco.
    when(store.questions(workspaceId, competitionId))
        .thenReturn(
            List.of(
                question(competitionId, "PUBLISHED", "a"),
                question(competitionId, "PUBLISHED", "b"),
                question(competitionId, "PUBLISHED", "c"),
                question(competitionId, "PUBLISHED", "d"),
                question(competitionId, "PUBLISHED", "e")));
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var second = service.startSimulation(workspaceId, competitionId, "", 3, 5);

    assertThat(second.status()).isEqualTo("READY");
    assertThat(second.jobId()).isNull();
    assertThat(second.simulation()).isNotNull();
    assertThat(second.simulation().items()).hasSize(5);
  }

  @Test
  void draftQuestionsNeverEnterASimulation() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    when(store.syllabus(workspaceId, competitionId))
        .thenReturn(Optional.of(approvedSyllabus(workspaceId, competitionId, subjectsOf(1))));
    when(store.questions(workspaceId, competitionId))
        .thenReturn(List.of(question(competitionId, "DRAFT", "a")));
    when(store.saveJob(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.startSimulation(workspaceId, competitionId, "", 3, 1);

    assertThat(result.status()).isEqualTo("PENDING");
  }

  @Test
  void aSecondSimulationDoesNotRepeatTheQuestionsOfTheFirst() {
    // Bug reportado: com limit(count) sobre a lista na ordem do banco, o simulado 2 recebia
    // exatamente as mesmas questões do 1 — nada marcava uma questão como já usada.
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);

    var bank = new ArrayList<Question>();
    for (int i = 1; i <= 6; i++) bank.add(question(competitionId, "PUBLISHED", "t" + i));
    when(store.questions(workspaceId, competitionId)).thenReturn(bank);
    when(store.saveSimulation(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var first = service.startSimulation(workspaceId, competitionId, "", 3, 3);
    assertThat(first.status()).isEqualTo("READY");
    var firstIds = first.simulation().items().stream().map(SimulationItem::questionId).toList();

    // A segunda chamada enxerga o primeiro simulado.
    when(store.simulations(workspaceId, competitionId)).thenReturn(List.of(first.simulation()));

    var second = service.startSimulation(workspaceId, competitionId, "", 3, 3);
    var secondIds = second.simulation().items().stream().map(SimulationItem::questionId).toList();

    assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
  }

  @Test
  void questionsAlreadyUsedAreOnlyReusedWhenThereAreNotEnoughUnused() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    // Pede 6 com 4 no banco: cai no ramo de geração e generationPayload lê o edital.
    when(store.syllabus(workspaceId, competitionId))
        .thenReturn(Optional.of(approvedSyllabus(workspaceId, competitionId, subjectsOf(1))));

    var bank = new ArrayList<Question>();
    for (int i = 1; i <= 4; i++) bank.add(question(competitionId, "PUBLISHED", "t" + i));
    when(store.questions(workspaceId, competitionId)).thenReturn(bank);

    // As 4 do banco já foram usadas num simulado anterior.
    var previous =
        new Simulation(
            UUID.randomUUID(),
            workspaceId,
            competitionId,
            null,
            "FINISHED",
            bank.stream()
                .map(q -> new SimulationItem(q.id(), q.topicId(), 0, 1, Instant.now()))
                .toList(),
            10,
            Instant.now(),
            Instant.now());
    when(store.simulations(workspaceId, competitionId)).thenReturn(List.of(previous));

    // Pedindo mais do que existe: tem que reaproveitar, senão o simulado nunca fecha.
    var result = service.startSimulation(workspaceId, competitionId, "", 3, 6);

    assertThat(result.status()).isEqualTo("PENDING");
    assertThat(result.bankAvailable()).isEqualTo(4);
    assertThat(result.toGenerate()).isEqualTo(2);
  }

  @Test
  void availabilityReportsHowManyTheAiWouldHaveToCreateBeforeTheUserClicks() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competitionId = UUID.randomUUID();
    withCompetition(workspaceId);
    // subjectId vazio => escopo nulo => o syllabus nem e consultado.
    when(store.questions(workspaceId, competitionId))
        .thenReturn(List.of(question(competitionId, "PUBLISHED", "a")));

    var availability = service.simulationAvailability(workspaceId, competitionId, "", 3, 10);

    assertThat(availability.publishedInScope()).isEqualTo(1);
    assertThat(availability.toGenerate()).isEqualTo(9);
    // Diagnóstico puro: não pode criar job nem gravar nada.
    assertThat(availability.usableForRequest()).isEqualTo(1);
  }

  private static Question question(UUID competitionId, String status, String topicId) {
    return new Question(
        UUID.randomUUID(),
        UUID.randomUUID(),
        competitionId,
        null,
        topicId,
        "AI_GENERATED",
        status,
        null,
        3,
        "Enunciado",
        List.of("a", "b", "c", "d"),
        0,
        "porque",
        List.of(),
        Instant.now(),
        Instant.now());
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

  // --- catálogo oficial de programas ------------------------------------------------------------
  // O objetivo do catálogo é o edital existir sem credencial de IA. Estes testes usam o arquivo
  // real
  // do classpath de propósito: um catálogo que passa no mock e está vazio no deploy não serve.

  @Test
  void catalogCarriesEveryProgramOfTheOfficialAnnex() {
    var programs = catalog().programas();

    // Anexo III do Edital 014/2026 (Seduc/CE): 14 cargos de professor.
    assertThat(programs)
        .extracting(ProgramaCatalog.ProgramaView::slug)
        .contains(
            "professor-matematica",
            "professor-biologia",
            "professor-fisica",
            "professor-quimica",
            "professor-geografia",
            "professor-historia",
            "professor-sociologia",
            "professor-filosofia",
            "professor-lingua-portuguesa",
            "professor-lingua-inglesa",
            "professor-lingua-espanhola",
            "professor-educacao-fisica",
            "professor-arte-educacao",
            "professor-aee");
    assertThat(programs).allSatisfy(p -> assertThat(p.subtopicos()).isPositive());
  }

  @Test
  void programCarriesBasicsPlusTheRoleAndTheRealWeighting() {
    var subjects = catalog().subjectsFor("professor-matematica");

    // 4 basics (P1) + 1 específica (P2).
    assertThat(subjects).hasSize(5);
    assertThat(subjects)
        .extracting(SyllabusSubject::name)
        .containsExactly(
            "Administração Pública",
            "Educação Brasileira",
            "Leitura e Interpretação de Dados e Indicadores Educacionais",
            "Língua Portuguesa",
            "Professor de Matemática");
    // Item 1.7.1 do edital: P1 são 30 questões em 4 matérias, P2 são 50 no cargo.
    assertThat(subjects.get(0).weight()).isEqualTo(7.5);
    assertThat(subjects.get(4).weight()).isEqualTo(50.0);
  }

  @Test
  void everyTopicIdIsUniqueInsideItsProgram() {
    for (var programa : catalog().programas()) {
      var ids = new java.util.ArrayList<String>();
      collectSubjectIds(catalog().subjectsFor(programa.slug()), ids);
      assertThat(ids).as(programa.slug()).doesNotHaveDuplicates();
    }
  }

  private static void collectSubjectIds(List<SyllabusSubject> subjects, List<String> out) {
    for (var s : subjects) collectTopicIds(s.topics(), out);
  }

  private static void collectTopicIds(List<SyllabusTopic> topics, List<String> out) {
    for (var t : topics) {
      out.add(t.id());
      collectTopicIds(t.children(), out);
    }
  }

  @Test
  void rejectsAProgramThatIsNotInTheCatalog() {
    assertThatThrownBy(() -> catalog().subjectsFor("professor-de-astronomia"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("professor-matematica");
  }

  @Test
  void onlySuggestsAProgramWhenTheRoleMatchesExactly() {
    assertThat(catalog().suggestForRole("Professor de Matemática"))
        .isEqualTo("professor-matematica");
    // Cargo genérico não pode virar chute: edital errado é pior do que pedir para escolher.
    assertThat(catalog().suggestForRole("Professor")).isNull();
    assertThat(catalog().suggestForRole("AnalistaJudicial")).isNull();
    assertThat(catalog().suggestForRole(null)).isNull();
  }

  @Test
  void appliesTheProgramAsDraftWithoutAnyDocument() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competition =
        Competition.create(
            workspaceId,
            "Seduc 2026",
            "Professor de Matemática",
            "Cebraspe",
            LocalDate.now().plusDays(120));
    when(competitions.find(workspaceId, competition.id())).thenReturn(Optional.of(competition));
    when(store.syllabus(workspaceId, competition.id())).thenReturn(Optional.empty());
    when(store.saveSyllabus(any())).thenAnswer(i -> i.getArgument(0));

    var syllabus = service.applyProgram(workspaceId, competition.id(), "professor-matematica");

    // Sem PDF: documentId nulo. É o que exigiu o V4.
    assertThat(syllabus.documentId()).isNull();
    assertThat(syllabus.version()).isOne();
    assertThat(syllabus.status()).isEqualTo("DRAFT");
    assertThat(syllabus.subjects()).hasSize(5);
    // Evidência é citação literal do anexo, não saída de modelo: confiança cheia.
    assertThat(syllabus.subjects().get(4).topics().get(0).evidence().confidence()).isEqualTo(1.0);
    // O concurso vai para revisão, igual ao fluxo de upload.
    assertThat(competition.status()).isEqualTo(Competition.Status.REVIEW);
  }

  @Test
  void refusesToOverwriteAnApprovedSyllabus() {
    var service = service();
    var workspaceId = UUID.randomUUID();
    var competition =
        Competition.create(workspaceId, "Seduc", "Professor de Matemática", "Cebraspe", null);
    when(competitions.find(workspaceId, competition.id())).thenReturn(Optional.of(competition));
    when(store.syllabus(workspaceId, competition.id()))
        .thenReturn(Optional.of(approvedSyllabus(workspaceId, competition.id(), List.of())));

    assertThatThrownBy(
            () -> service.applyProgram(workspaceId, competition.id(), "professor-matematica"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aprovada");
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
            null,
            3000);
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
    return new StudyApplicationService(
        competitions, store, onboardings, objects, events, catalog());
  }

  /** Catálogo real do classpath: os testes de plano dependem de conteúdo de verdade. */
  private static ProgramaCatalog catalog() {
    return new ProgramaCatalog(JsonMapper.builder().build(), "programas/seduc-2026.json");
  }
}
