package br.com.ciclo.study.presentation;

import br.com.ciclo.study.application.StudyApplicationService;
import br.com.ciclo.study.application.StudyApplicationService.*;
import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class StudyController {
  private final StudyApplicationService study;

  public StudyController(StudyApplicationService study) {
    this.study = study;
  }

  @PostMapping("/competitions")
  @ResponseStatus(HttpStatus.CREATED)
  CompetitionView create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCompetition r) {
    return view(
        study.createCompetition(workspace(jwt), r.title(), r.role(), r.board(), r.examDate()));
  }

  @GetMapping("/onboarding")
  OnboardingView onboarding(@AuthenticationPrincipal Jwt jwt) {
    return study.onboarding(workspace(jwt));
  }

  @PatchMapping("/onboarding")
  OnboardingView onboarding(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateOnboarding r) {
    return study.updateOnboarding(workspace(jwt), r.dismissed(), r.competitionId());
  }

  @GetMapping("/competitions")
  List<CompetitionView> list(@AuthenticationPrincipal Jwt jwt) {
    return study.listCompetitions(workspace(jwt)).stream().map(StudyController::view).toList();
  }

  @GetMapping("/competitions/{id}")
  CompetitionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return view(study.getCompetition(workspace(jwt), id));
  }

  @DeleteMapping("/competitions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    study.deleteCompetition(workspace(jwt), id);
  }

  @PostMapping(
      value = "/competitions/{id}/documents/upload",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  UploadResult upload(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    pdf(file);
    return study.uploadDocument(workspace(jwt), id, file.getOriginalFilename(), file.getBytes());
  }

  @GetMapping("/competitions/{id}/documents")
  List<Document> documents(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.listDocuments(workspace(jwt), id);
  }

  @PostMapping("/competitions/{id}/documents/{documentId}/process")
  @ResponseStatus(HttpStatus.ACCEPTED)
  UploadResult reprocess(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID documentId) {
    return study.reprocessDocument(workspace(jwt), id, documentId);
  }

  @GetMapping("/processing-jobs/{id}")
  Job job(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.job(workspace(jwt), id);
  }

  @GetMapping("/competitions/{id}/syllabus")
  Syllabus syllabus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.syllabus(workspace(jwt), id);
  }

  @PutMapping("/competitions/{id}/syllabus/draft")
  Syllabus revise(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Subjects r) {
    return study.reviseSyllabus(workspace(jwt), id, r.subjects());
  }

  @PostMapping("/competitions/{id}/syllabus/approve")
  Syllabus approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.approveSyllabus(workspace(jwt), id);
  }

  @PostMapping("/competitions/{id}/study-plan")
  Plan plan(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody GeneratePlan r) {
    return study.generatePlan(workspace(jwt), id, r.examDate(), r.availability());
  }

  @GetMapping("/competitions/{id}/study-plan")
  Plan plan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.getPlan(workspace(jwt), id);
  }

  @PostMapping("/competitions/{id}/study-plan/sessions/{sessionId}/start")
  Plan start(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID sessionId) {
    return study.startSession(workspace(jwt), id, sessionId);
  }

  @PostMapping("/competitions/{id}/study-plan/sessions/{sessionId}/complete")
  Plan complete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID sessionId) {
    return study.completeSession(workspace(jwt), id, sessionId);
  }

  @PostMapping("/flashcards")
  @ResponseStatus(HttpStatus.CREATED)
  Flashcard flashcard(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateFlashcard r) {
    return study.createFlashcard(workspace(jwt), r.competitionId(), r.front(), r.back());
  }

  @GetMapping("/flashcards")
  List<Flashcard> flashcards(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID competitionId) {
    return study.flashcards(workspace(jwt), competitionId);
  }

  @DeleteMapping("/flashcards/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteFlashcard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    study.deleteFlashcard(workspace(jwt), id);
  }

  @PostMapping("/competitions/{id}/simulations")
  ResponseEntity<StartSimulationResult> start(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestBody(required = false) StartSimulation r) {
    if (r == null) r = new StartSimulation(null, 3, 10);
    var result =
        study.startSimulation(
            workspace(jwt),
            id,
            r.subjectId(),
            r.difficulty() == null ? 3 : r.difficulty(),
            r.count() == null ? 10 : r.count());
    return ResponseEntity.status(result.jobId() == null ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
        .body(result);
  }

  @GetMapping("/simulations")
  List<Simulation> simulations(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID competitionId) {
    return study.simulations(workspace(jwt), competitionId);
  }

  @GetMapping("/simulations/{id}")
  Simulation simulation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.simulation(workspace(jwt), id);
  }

  @GetMapping("/simulations/{id}/questions")
  List<Question> simulationQuestions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.simulationQuestions(workspace(jwt), id);
  }

  @PatchMapping("/simulations/{id}")
  Simulation rename(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Rename r) {
    return study.renameSimulation(workspace(jwt), id, r.title());
  }

  @PostMapping("/simulations/{id}/answer")
  Simulation answer(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Answer r) {
    return study.answer(workspace(jwt), id, r.questionId(), r.selectedIndex());
  }

  @PostMapping("/simulations/{id}/finish")
  Simulation finish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.finish(workspace(jwt), id);
  }

  @PostMapping(
      value = "/competitions/{id}/mock-exams/upload",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  UploadResult mock(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    pdf(file);
    return study.uploadMockExam(workspace(jwt), id, file.getOriginalFilename(), file.getBytes());
  }

  @GetMapping("/competitions/{id}/mock-exams")
  List<MockExamSource> mockExams(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.mockExamSources(workspace(jwt), id);
  }

  @GetMapping("/mock-exams/{id}/questions")
  List<Question> mockExamQuestions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.mockExamQuestions(workspace(jwt), id);
  }

  @PostMapping("/questions/{id}/review")
  Question review(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review r) {
    return study.reviewQuestion(workspace(jwt), id, r.decision());
  }

  @PostMapping("/competitions/{id}/mock-exams/classify")
  @ResponseStatus(HttpStatus.ACCEPTED)
  UploadResult classify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return study.classifyMockExamQuestions(workspace(jwt), id);
  }

  @GetMapping("/progress")
  Progress progress(@AuthenticationPrincipal Jwt jwt) {
    return study.progress(workspace(jwt));
  }

  private static UUID workspace(Jwt jwt) {
    return UUID.fromString(jwt.getClaimAsString("workspace_id"));
  }

  private static CompetitionView view(Competition competition) {
    return new CompetitionView(
        competition.id(),
        competition.workspaceId(),
        competition.title(),
        competition.role(),
        competition.board(),
        competition.examDate(),
        competition.status(),
        competition.createdAt(),
        competition.updatedAt());
  }

  private static void pdf(MultipartFile file) {
    if (!MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType()))
      throw new IllegalArgumentException("Envie um arquivo PDF.");
  }

  record CreateCompetition(
      @Size(min = 3, max = 160) String title,
      @NotBlank String role,
      @NotBlank String board,
      LocalDate examDate) {}

  record CompetitionView(
      UUID id,
      UUID workspaceId,
      String title,
      String role,
      String board,
      LocalDate examDate,
      Competition.Status status,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}

  record UpdateOnboarding(@NotNull Boolean dismissed, UUID competitionId) {}

  record Subjects(@NotNull List<SyllabusSubject> subjects) {}

  record GeneratePlan(@Future LocalDate examDate, @NotEmpty List<Availability> availability) {}

  record CreateFlashcard(UUID competitionId, @NotBlank String front, @NotBlank String back) {}

  record StartSimulation(
      String subjectId, @Min(1) @Max(5) Integer difficulty, @Min(1) @Max(20) Integer count) {}

  record Answer(@NotNull UUID questionId, @Min(0) int selectedIndex) {}

  record Rename(@NotBlank @Size(max = 160) String title) {}

  record Review(@Pattern(regexp = "publish|reject") String decision) {}
}
