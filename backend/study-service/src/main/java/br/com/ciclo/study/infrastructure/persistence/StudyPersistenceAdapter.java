package br.com.ciclo.study.infrastructure.persistence;

import br.com.ciclo.study.application.StudyApplicationService.JobWorkspaceLookup;
import br.com.ciclo.study.application.StudyPorts.*;
import br.com.ciclo.study.domain.Competition;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StudyPersistenceAdapter
    implements Competitions, Store, Onboardings, JobWorkspaceLookup {
  private final JdbcTemplate db;
  private final JsonMapper json;

  public StudyPersistenceAdapter(JdbcTemplate db, JsonMapper json) {
    this.db = db;
    this.json = json;
  }

  public Competition save(Competition c) {
    db.update(
        "INSERT INTO"
            + " competitions(id,workspace_id,title,role,board,exam_date,status,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " title=excluded.title,role=excluded.role,board=excluded.board,exam_date=excluded.exam_date,status=excluded.status,updated_at=excluded.updated_at",
        c.id(),
        c.workspaceId(),
        c.title(),
        c.role(),
        c.board(),
        c.examDate(),
        c.status().name(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
    return c;
  }

  public Optional<Competition> find(UUID w, UUID id) {
    return db
        .query("SELECT * FROM competitions WHERE workspace_id=? AND id=?", this::competition, w, id)
        .stream()
        .findFirst();
  }

  public List<Competition> list(UUID w) {
    return db.query(
        "SELECT * FROM competitions WHERE workspace_id=? ORDER BY created_at DESC",
        this::competition,
        w);
  }

  public void delete(UUID w, UUID id) {
    db.update("DELETE FROM competitions WHERE workspace_id=? AND id=?", w, id);
  }

  public Document saveDocument(Document d) {
    db.update(
        "INSERT INTO"
            + " documents(id,workspace_id,competition_id,version,file_name,object_key,size_bytes,status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status",
        d.id(),
        d.workspaceId(),
        d.competitionId(),
        d.version(),
        d.fileName(),
        d.objectKey(),
        d.sizeBytes(),
        d.status(),
        ts(d.createdAt()));
    return d;
  }

  public List<Document> documents(UUID w, UUID c) {
    return db.query(
        "SELECT * FROM documents WHERE workspace_id=? AND competition_id=? ORDER BY version DESC",
        this::document,
        w,
        c);
  }

  public Optional<Document> document(UUID w, UUID id) {
    return db
        .query("SELECT * FROM documents WHERE workspace_id=? AND id=?", this::document, w, id)
        .stream()
        .findFirst();
  }

  public MockExamSource saveMockExamSource(MockExamSource source) {
    db.update(
        "INSERT INTO"
            + " mock_exam_sources(id,workspace_id,competition_id,version,file_name,object_key,size_bytes,status,board,question_count,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " status=excluded.status,board=excluded.board,question_count=excluded.question_count,updated_at=excluded.updated_at",
        source.id(),
        source.workspaceId(),
        source.competitionId(),
        source.version(),
        source.fileName(),
        source.objectKey(),
        source.sizeBytes(),
        source.status(),
        source.board(),
        source.questionCount(),
        ts(source.createdAt()),
        ts(source.updatedAt()));
    return source;
  }

  public Optional<MockExamSource> mockExamSource(UUID w, UUID id) {
    return db
        .query(
            "SELECT * FROM mock_exam_sources WHERE workspace_id=? AND id=?",
            this::mockExamSource,
            w,
            id)
        .stream()
        .findFirst();
  }

  public List<MockExamSource> mockExamSources(UUID w, UUID c) {
    return db.query(
        "SELECT * FROM mock_exam_sources WHERE workspace_id=? AND competition_id=? ORDER BY version"
            + " DESC",
        this::mockExamSource,
        w,
        c);
  }

  public Job saveJob(Job j) {
    db.update(
        "INSERT INTO"
            + " processing_jobs(id,workspace_id,type,aggregate_id,status,progress,error_code,error_message,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " status=excluded.status,progress=excluded.progress,error_code=excluded.error_code,error_message=excluded.error_message,updated_at=excluded.updated_at",
        j.id(),
        j.workspaceId(),
        j.type(),
        j.aggregateId(),
        j.status(),
        j.progress(),
        j.errorCode(),
        j.errorMessage(),
        ts(j.createdAt()),
        ts(j.updatedAt()));
    return j;
  }

  public Optional<Job> job(UUID w, UUID id) {
    return db
        .query("SELECT * FROM processing_jobs WHERE workspace_id=? AND id=?", this::job, w, id)
        .stream()
        .findFirst();
  }

  public Optional<Job> latestJob(UUID w, UUID aggregateId, String type) {
    return db
        .query(
            "SELECT * FROM processing_jobs WHERE workspace_id=? AND aggregate_id=? AND type=?"
                + " ORDER BY created_at DESC LIMIT 1",
            this::job,
            w,
            aggregateId,
            type)
        .stream()
        .findFirst();
  }

  public Optional<UUID> workspaceForJob(UUID id) {
    return db
        .query(
            "SELECT workspace_id FROM processing_jobs WHERE id=?",
            (r, n) -> r.getObject(1, UUID.class),
            id)
        .stream()
        .findFirst();
  }

  public Syllabus saveSyllabus(Syllabus s) {
    db.update(
        "INSERT INTO"
            + " syllabi(id,workspace_id,competition_id,document_id,version,status,subjects,approved_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,CAST(? AS jsonb),?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " status=excluded.status,subjects=excluded.subjects,approved_at=excluded.approved_at,updated_at=excluded.updated_at",
        s.id(),
        s.workspaceId(),
        s.competitionId(),
        s.documentId(),
        s.version(),
        s.status(),
        string(s.subjects()),
        ts(s.approvedAt()),
        ts(s.createdAt()),
        ts(s.updatedAt()));
    return s;
  }

  public Optional<Syllabus> syllabus(UUID w, UUID c) {
    return db
        .query(
            "SELECT * FROM syllabi WHERE workspace_id=? AND competition_id=? ORDER BY version DESC"
                + " LIMIT 1",
            this::syllabus,
            w,
            c)
        .stream()
        .findFirst();
  }

  public Plan savePlan(Plan p) {
    db.update(
        "INSERT INTO"
            + " study_plans(id,workspace_id,competition_id,version,availability,sessions,created_at,updated_at)"
            + " VALUES(?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?) ON CONFLICT(id) DO UPDATE SET"
            + " availability=excluded.availability,sessions=excluded.sessions,updated_at=excluded.updated_at",
        p.id(),
        p.workspaceId(),
        p.competitionId(),
        p.version(),
        string(p.availability()),
        string(p.sessions()),
        ts(p.createdAt()),
        ts(p.updatedAt()));
    return p;
  }

  @Transactional
  public Plan savePlanAndCompleteOnboarding(Plan p) {
    savePlan(p);
    Instant now = Instant.now();
    db.update(
        "INSERT INTO onboarding_states"
            + " (workspace_id,competition_id,dismissed_at,completed_at,created_at,updated_at)"
            + " VALUES(?,?,NULL,?,?,?) ON CONFLICT(workspace_id) DO UPDATE SET"
            + " competition_id=excluded.competition_id,dismissed_at=NULL,"
            + " completed_at=COALESCE(onboarding_states.completed_at,excluded.completed_at),"
            + " updated_at=excluded.updated_at",
        p.workspaceId(),
        p.competitionId(),
        ts(now),
        ts(now),
        ts(now));
    return p;
  }

  public Optional<Plan> plan(UUID w, UUID c) {
    return db
        .query(
            "SELECT * FROM study_plans WHERE workspace_id=? AND competition_id=? ORDER BY version"
                + " DESC LIMIT 1",
            this::plan,
            w,
            c)
        .stream()
        .findFirst();
  }

  public Flashcard saveFlashcard(Flashcard f) {
    db.update(
        "INSERT INTO flashcards(id,workspace_id,competition_id,front,back,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " front=excluded.front,back=excluded.back,updated_at=excluded.updated_at",
        f.id(),
        f.workspaceId(),
        f.competitionId(),
        f.front(),
        f.back(),
        ts(f.createdAt()),
        ts(f.updatedAt()));
    return f;
  }

  public List<Flashcard> flashcards(UUID w, UUID c) {
    if (c == null)
      return db.query(
          "SELECT * FROM flashcards WHERE workspace_id=? ORDER BY created_at DESC",
          this::flashcard,
          w);
    return db.query(
        "SELECT * FROM flashcards WHERE workspace_id=? AND competition_id=? ORDER BY created_at"
            + " DESC",
        this::flashcard,
        w,
        c);
  }

  public void deleteFlashcard(UUID w, UUID id) {
    db.update("DELETE FROM flashcards WHERE workspace_id=? AND id=?", w, id);
  }

  public Question saveQuestion(Question q) {
    db.update(
        "INSERT INTO"
            + " questions(id,workspace_id,competition_id,mock_exam_source_id,topic_id,origin,status,board_style,difficulty,statement,alternatives,correct_index,explanation,sources,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,CAST(? AS jsonb),?,?) ON"
            + " CONFLICT(id) DO UPDATE SET"
            + " topic_id=excluded.topic_id,status=excluded.status,updated_at=excluded.updated_at",
        q.id(),
        q.workspaceId(),
        q.competitionId(),
        q.mockExamSourceId(),
        q.topicId(),
        q.origin(),
        q.status(),
        q.boardStyle(),
        q.difficulty(),
        q.statement(),
        string(q.alternatives()),
        q.correctIndex(),
        q.explanation(),
        string(q.sources()),
        ts(q.createdAt()),
        ts(q.updatedAt()));
    return q;
  }

  public List<Question> questions(UUID w, UUID c) {
    if (c == null)
      return db.query(
          "SELECT * FROM questions WHERE workspace_id=? ORDER BY created_at", this::question, w);
    return db.query(
        "SELECT * FROM questions WHERE workspace_id=? AND competition_id=? ORDER BY created_at",
        this::question,
        w,
        c);
  }

  public List<Question> questionsByMockExamSource(UUID w, UUID sourceId) {
    return db.query(
        "SELECT * FROM questions WHERE workspace_id=? AND mock_exam_source_id=? ORDER BY"
            + " created_at",
        this::question,
        w,
        sourceId);
  }

  public Simulation saveSimulation(Simulation s) {
    db.update(
        "INSERT INTO"
            + " simulations(id,workspace_id,competition_id,title,status,items,duration_minutes,started_at,finished_at)"
            + " VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " title=excluded.title,status=excluded.status,items=excluded.items,duration_minutes=excluded.duration_minutes,finished_at=excluded.finished_at",
        s.id(),
        s.workspaceId(),
        s.competitionId(),
        s.title(),
        s.status(),
        string(s.items()),
        s.durationMinutes(),
        ts(s.startedAt()),
        ts(s.finishedAt()));
    return s;
  }

  public Optional<Simulation> simulation(UUID w, UUID id) {
    return db
        .query("SELECT * FROM simulations WHERE workspace_id=? AND id=?", this::simulation, w, id)
        .stream()
        .findFirst();
  }

  public List<Simulation> simulations(UUID w, UUID c) {
    if (c == null)
      return db.query(
          "SELECT * FROM simulations WHERE workspace_id=? ORDER BY started_at DESC",
          this::simulation,
          w);
    return db.query(
        "SELECT * FROM simulations WHERE workspace_id=? AND competition_id=? ORDER BY started_at"
            + " DESC",
        this::simulation,
        w,
        c);
  }

  public Optional<OnboardingState> find(UUID w) {
    return db
        .query("SELECT * FROM onboarding_states WHERE workspace_id=?", this::onboarding, w)
        .stream()
        .findFirst();
  }

  public OnboardingState save(OnboardingState value) {
    db.update(
        "INSERT INTO onboarding_states"
            + " (workspace_id,competition_id,dismissed_at,completed_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT(workspace_id) DO UPDATE SET"
            + " competition_id=excluded.competition_id,dismissed_at=excluded.dismissed_at,"
            + " completed_at=excluded.completed_at,updated_at=excluded.updated_at",
        value.workspaceId(),
        value.competitionId(),
        ts(value.dismissedAt()),
        ts(value.completedAt()),
        ts(value.createdAt()),
        ts(value.updatedAt()));
    return value;
  }

  private Competition competition(ResultSet r, int n) throws SQLException {
    return new Competition(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        r.getString("title"),
        r.getString("role"),
        r.getString("board"),
        r.getObject("exam_date", LocalDate.class),
        Competition.Status.valueOf(r.getString("status")),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private OnboardingState onboarding(ResultSet r, int n) throws SQLException {
    return new OnboardingState(
        uuid(r, "workspace_id"),
        (UUID) r.getObject("competition_id"),
        instant(r, "dismissed_at"),
        instant(r, "completed_at"),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Document document(ResultSet r, int n) throws SQLException {
    return new Document(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        r.getInt("version"),
        r.getString("file_name"),
        r.getString("object_key"),
        r.getLong("size_bytes"),
        r.getString("status"),
        instant(r, "created_at"));
  }

  private Job job(ResultSet r, int n) throws SQLException {
    return new Job(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        r.getString("type"),
        uuid(r, "aggregate_id"),
        r.getString("status"),
        r.getInt("progress"),
        r.getString("error_code"),
        r.getString("error_message"),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private MockExamSource mockExamSource(ResultSet r, int n) throws SQLException {
    return new MockExamSource(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        r.getInt("version"),
        r.getString("file_name"),
        r.getString("object_key"),
        r.getLong("size_bytes"),
        r.getString("status"),
        r.getString("board"),
        r.getInt("question_count"),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Syllabus syllabus(ResultSet r, int n) throws SQLException {
    return new Syllabus(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        uuid(r, "document_id"),
        r.getInt("version"),
        r.getString("status"),
        read(r.getString("subjects"), new TypeReference<List<SyllabusSubject>>() {}),
        instant(r, "approved_at"),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Plan plan(ResultSet r, int n) throws SQLException {
    return new Plan(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        r.getInt("version"),
        read(r.getString("availability"), new TypeReference<List<Availability>>() {}),
        read(r.getString("sessions"), new TypeReference<List<StudySession>>() {}),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Flashcard flashcard(ResultSet r, int n) throws SQLException {
    return new Flashcard(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        (UUID) r.getObject("competition_id"),
        r.getString("front"),
        r.getString("back"),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Question question(ResultSet r, int n) throws SQLException {
    return new Question(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        (UUID) r.getObject("mock_exam_source_id"),
        r.getString("topic_id"),
        r.getString("origin"),
        r.getString("status"),
        r.getString("board_style"),
        r.getInt("difficulty"),
        r.getString("statement"),
        read(r.getString("alternatives"), new TypeReference<List<String>>() {}),
        r.getInt("correct_index"),
        r.getString("explanation"),
        read(r.getString("sources"), new TypeReference<List<String>>() {}),
        instant(r, "created_at"),
        instant(r, "updated_at"));
  }

  private Simulation simulation(ResultSet r, int n) throws SQLException {
    return new Simulation(
        uuid(r, "id"),
        uuid(r, "workspace_id"),
        uuid(r, "competition_id"),
        r.getString("title"),
        r.getString("status"),
        read(r.getString("items"), new TypeReference<List<SimulationItem>>() {}),
        r.getInt("duration_minutes"),
        instant(r, "started_at"),
        instant(r, "finished_at"));
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String string(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID uuid(ResultSet r, String name) throws SQLException {
    return r.getObject(name, UUID.class);
  }

  private static Instant instant(ResultSet r, String name) throws SQLException {
    Timestamp value = r.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
