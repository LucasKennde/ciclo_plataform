import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface User {
  id: string;
  email: string;
  displayName: string;
  role: 'STUDENT' | 'ADMIN';
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED';
}
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: User;
  workspaceId: string;
}
export interface RegistrationResponse {
  userId: string;
  workspaceId: string;
  email: string;
  emailVerificationRequired: boolean;
}
export interface IdentitySettings {
  emailVerificationRequired: boolean;
  updatedAt: string;
  updatedBy: string;
}
export interface MailConfiguration {
  mode: 'LOCAL_CAPTURE' | 'SMTP';
  configured: boolean;
  host: string;
  port: number;
  usernameMasked: string;
  from: string;
  authentication: boolean;
  startTls: boolean;
}
export interface Competition {
  id: string;
  workspaceId: string;
  title: string;
  role: string;
  board: string;
  examDate: string | null;
  status: 'DRAFT' | 'PROCESSING' | 'REVIEW' | 'ACTIVE' | 'ARCHIVED';
  createdAt: string;
  updatedAt: string;
}
export interface OnboardingStep {
  key: 'COMPETITION' | 'DOCUMENT' | 'REVIEW' | 'AVAILABILITY' | 'COMPLETED';
  label: string;
  status: 'DONE' | 'CURRENT' | 'PENDING';
}
export interface OnboardingState {
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'DISMISSED' | 'COMPLETED';
  currentStep: 'COMPETITION' | 'DOCUMENT' | 'PROCESSING' | 'REVIEW' | 'AVAILABILITY' | 'COMPLETED';
  competitionId: string | null;
  dismissedAt: string | null;
  completedAt: string | null;
  steps: OnboardingStep[];
  processingJob: ProcessingJob | null;
}
export interface ProcessingJob {
  id: string;
  aggregateId: string;
  type: string;
  status: string;
  progress: number;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface CompetitionDocument {
  id: string;
  competitionId: string;
  version: number;
  fileName: string;
  objectKey: string;
  sizeBytes: number;
  status: 'UPLOADED' | 'EXTRACTING' | 'NEEDS_REVIEW' | 'APPROVED' | 'FAILED';
  createdAt: string;
}
export interface SyllabusTopic {
  id: string;
  name: string;
  weight: number;
  evidence: { page: number | null; excerpt: string; confidence: number } | null;
  children: SyllabusTopic[];
}
export interface SyllabusSubject {
  id: string;
  name: string;
  weight: number;
  topics: SyllabusTopic[];
}
export interface Syllabus {
  id: string;
  competitionId: string;
  status: string;
  subjects: SyllabusSubject[];
  approvedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
export interface AvailabilitySlot {
  weekday: number;
  minutes: number;
}
export interface PlannedSession {
  id: string;
  topicId: string;
  subjectName: string;
  topicName: string;
  date: string;
  minutes: number;
  kind: 'study' | 'review' | 'questions';
  status: 'planned' | 'completed' | 'missed';
}
export interface StudyPlan {
  id: string;
  competitionId: string;
  sessions: PlannedSession[];
  availability: AvailabilitySlot[];
  createdAt?: string;
  updatedAt?: string;
}
export interface Flashcard {
  id: string;
  competitionId: string | null;
  front: string;
  back: string;
  createdAt: string;
}
export interface Simulation {
  id: string;
  competitionId: string;
  title: string | null;
  status: 'IN_PROGRESS' | 'FINISHED';
  items: Array<{
    questionId: string;
    topicId: string;
    selectedIndex: number | null;
    correctIndex: number;
    answeredAt?: string | null;
  }>;
  durationMinutes: number;
  startedAt: string;
  finishedAt: string | null;
}
export interface Question {
  id: string;
  competitionId: string;
  topicId: string;
  origin: 'LICENSED' | 'AI_GENERATED' | 'SYLLABUS';
  status: 'DRAFT' | 'PUBLISHED' | 'REJECTED';
  boardStyle: string | null;
  difficulty: number;
  statement: string;
  alternatives: string[];
  correctIndex: number;
  explanation: string;
  sources: string[];
}
export interface SimulationRun {
  simulation: Simulation;
  questions: Question[];
}
export interface SimulationStart extends SimulationRun {
  jobId: string | null;
  status: string;
}
export interface MockExamSource {
  id: string;
  competitionId: string;
  version: number;
  fileName: string;
  status: 'UPLOADED' | 'EXTRACTING' | 'COMPLETED' | 'FAILED';
  board: string | null;
  questionCount: number;
  createdAt: string;
}
export interface Progress {
  xp: number;
  level: number;
  streak: number;
  completedSessions: number;
  totalAnswered: number;
  averageAccuracy: number;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface AiPolicy {
  perMinute: number;
  perHour: number;
  perDay: number;
  perPrincipalTokensDay: number;
  globalPerMinute: number;
  globalTokensDay: number;
  killSwitch: boolean;
}
export interface AiDashboard {
  summary: {
    requests: number;
    totalTokens: number;
    estimatedCostUsd: number;
    blocked: number;
    errors: number;
  };
  events: any[];
  consumers: any[];
  blocks: any[];
  settings: AiPolicy;
}

@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  register(body: {
    email: string;
    displayName: string;
    password: string;
    workspaceName: string;
  }): Observable<RegistrationResponse> {
    return this.http.post<RegistrationResponse>('/api/v1/auth/register', body);
  }
  verifyEmail(token: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/verify-email', { token });
  }
  login(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(
      '/api/v1/auth/login',
      { email, password },
      { withCredentials: true },
    );
  }
  refresh(): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/v1/auth/refresh', {}, { withCredentials: true });
  }
  logout(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', {}, { withCredentials: true });
  }
  me(): Observable<User> {
    return this.http.get<User>('/api/v1/me', { withCredentials: true });
  }
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/forgot-password', { email });
  }
  resetPassword(token: string, password: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/reset-password', { token, password });
  }
  competitions(): Observable<Competition[]> {
    return this.http.get<Competition[]>('/api/v1/competitions');
  }
  competition(id: string): Observable<Competition> {
    return this.http.get<Competition>(`/api/v1/competitions/${id}`);
  }
  createCompetition(body: Partial<Competition>): Observable<Competition> {
    return this.http.post<Competition>('/api/v1/competitions', body);
  }
  deleteCompetition(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/competitions/${id}`);
  }
  documents(id: string): Observable<CompetitionDocument[]> {
    return this.http.get<CompetitionDocument[]>(`/api/v1/competitions/${id}/documents`);
  }
  uploadDocument(
    id: string,
    file: File,
  ): Observable<{ documentId: string; jobId: string; statusUrl: string }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ documentId: string; jobId: string; statusUrl: string }>(
      `/api/v1/competitions/${id}/documents/upload`,
      form,
    );
  }
  job(id: string): Observable<ProcessingJob> {
    return this.http.get<ProcessingJob>(`/api/v1/processing-jobs/${id}`);
  }
  reprocessDocument(
    competitionId: string,
    documentId: string,
  ): Observable<{ documentId: string; jobId: string; statusUrl: string }> {
    return this.http.post<{ documentId: string; jobId: string; statusUrl: string }>(
      `/api/v1/competitions/${competitionId}/documents/${documentId}/process`,
      {},
    );
  }
  syllabus(id: string): Observable<Syllabus> {
    return this.http.get<Syllabus>(`/api/v1/competitions/${id}/syllabus`);
  }
  approveSyllabus(id: string): Observable<Syllabus> {
    return this.http.post<Syllabus>(`/api/v1/competitions/${id}/syllabus/approve`, {});
  }
  reviseSyllabus(id: string, subjects: SyllabusSubject[]): Observable<Syllabus> {
    return this.http.put<Syllabus>(`/api/v1/competitions/${id}/syllabus/draft`, { subjects });
  }
  onboarding(): Observable<OnboardingState> {
    return this.http.get<OnboardingState>('/api/v1/onboarding');
  }
  updateOnboarding(body: {
    dismissed: boolean;
    competitionId?: string | null;
  }): Observable<OnboardingState> {
    return this.http.patch<OnboardingState>('/api/v1/onboarding', body);
  }
  plan(id: string): Observable<StudyPlan> {
    return this.http.get<StudyPlan>(`/api/v1/competitions/${id}/study-plan`);
  }
  generatePlan(
    id: string,
    body: { examDate: string; availability: Array<{ weekday: number; minutes: number }> },
  ): Observable<StudyPlan> {
    return this.http.post<StudyPlan>(`/api/v1/competitions/${id}/study-plan`, body);
  }
  completeSession(competitionId: string, sessionId: string): Observable<StudyPlan> {
    return this.http.post<StudyPlan>(
      `/api/v1/competitions/${competitionId}/study-plan/sessions/${sessionId}/complete`,
      {},
    );
  }
  flashcards(competitionId?: string): Observable<Flashcard[]> {
    return this.http.get<Flashcard[]>('/api/v1/flashcards', {
      params: competitionId ? { competitionId } : {},
    });
  }
  createFlashcard(body: {
    competitionId?: string | null;
    front: string;
    back: string;
  }): Observable<Flashcard> {
    return this.http.post<Flashcard>('/api/v1/flashcards', body);
  }
  deleteFlashcard(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/flashcards/${id}`);
  }
  simulations(competitionId?: string): Observable<Simulation[]> {
    return this.http.get<Simulation[]>('/api/v1/simulations', {
      params: competitionId ? { competitionId } : {},
    });
  }
  startSimulation(competitionId: string, body: object = {}): Observable<SimulationStart> {
    return this.http.post<SimulationStart>(
      `/api/v1/competitions/${competitionId}/simulations`,
      body,
    );
  }
  simulation(id: string): Observable<Simulation> {
    return this.http.get<Simulation>(`/api/v1/simulations/${id}`);
  }
  simulationQuestions(id: string): Observable<Question[]> {
    return this.http.get<Question[]>(`/api/v1/simulations/${id}/questions`);
  }
  renameSimulation(id: string, title: string): Observable<Simulation> {
    return this.http.patch<Simulation>(`/api/v1/simulations/${id}`, { title });
  }
  answerSimulation(id: string, questionId: string, selectedIndex: number): Observable<Simulation> {
    return this.http.post<Simulation>(`/api/v1/simulations/${id}/answer`, {
      questionId,
      selectedIndex,
    });
  }
  finishSimulation(id: string): Observable<Simulation> {
    return this.http.post<Simulation>(`/api/v1/simulations/${id}/finish`, {});
  }
  uploadMockExam(
    competitionId: string,
    file: File,
  ): Observable<{ documentId: string; jobId: string; statusUrl: string }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ documentId: string; jobId: string; statusUrl: string }>(
      `/api/v1/competitions/${competitionId}/mock-exams/upload`,
      form,
    );
  }
  mockExams(competitionId: string): Observable<MockExamSource[]> {
    return this.http.get<MockExamSource[]>(`/api/v1/competitions/${competitionId}/mock-exams`);
  }
  mockExamQuestions(id: string): Observable<Question[]> {
    return this.http.get<Question[]>(`/api/v1/mock-exams/${id}/questions`);
  }
  reviewQuestion(id: string, decision: 'publish' | 'reject'): Observable<Question> {
    return this.http.post<Question>(`/api/v1/questions/${id}/review`, { decision });
  }
  classifyMockExamQuestions(
    competitionId: string,
  ): Observable<{ jobId: string; statusUrl: string }> {
    return this.http.post<{ jobId: string; statusUrl: string }>(
      `/api/v1/competitions/${competitionId}/mock-exams/classify`,
      {},
    );
  }
  progress(): Observable<Progress> {
    return this.http.get<Progress>('/api/v1/progress');
  }
  adminDashboard(): Observable<any> {
    return this.http.get<any>('/api/admin/v1/dashboard');
  }
  users(search = '', status = '', page = 0): Observable<Page<User>> {
    let params = new HttpParams().set('search', search).set('page', page).set('size', 20);
    if (status) params = params.set('status', status);
    return this.http.get<Page<User>>('/api/admin/v1/users', { params });
  }
  updateUser(id: string, body: { displayName?: string; status?: string }): Observable<User> {
    return this.http.patch<User>(`/api/admin/v1/users/${id}`, body);
  }
  revokeSessions(id: string): Observable<void> {
    return this.http.post<void>(`/api/admin/v1/users/${id}/revoke-sessions`, {});
  }
  confirmUserEmail(id: string): Observable<User> {
    return this.http.post<User>(`/api/admin/v1/users/${id}/confirm-email`, {});
  }
  resendUserVerification(id: string): Observable<void> {
    return this.http.post<void>(`/api/admin/v1/users/${id}/resend-verification`, {});
  }
  identitySettings(): Observable<IdentitySettings> {
    return this.http.get<IdentitySettings>('/api/admin/v1/identity/settings');
  }
  updateIdentitySettings(emailVerificationRequired: boolean): Observable<IdentitySettings> {
    return this.http.put<IdentitySettings>('/api/admin/v1/identity/settings', {
      emailVerificationRequired,
    });
  }
  mailConfiguration(): Observable<MailConfiguration> {
    return this.http.get<MailConfiguration>('/api/admin/v1/identity/mail');
  }
  testMail(email: string): Observable<void> {
    return this.http.post<void>('/api/admin/v1/identity/mail/test', { email });
  }
  settings(): Observable<any> {
    return this.http.get<any>('/api/admin/v1/settings');
  }
  updateSettings(body: any): Observable<any> {
    return this.http.put<any>('/api/admin/v1/settings', body);
  }
  aiProviders(): Observable<any> {
    return this.http.get<any>('/api/admin/v1/ai/providers');
  }
  saveProviderKey(provider: string, apiKey: string, force = false): Observable<any> {
    return this.http.put<any>(`/api/admin/v1/ai/providers/${provider}/key`, { apiKey, force });
  }
  testProvider(provider: string): Observable<any> {
    return this.http.post<any>(`/api/admin/v1/ai/providers/${provider}/test`, {});
  }
  updateRoutes(routes: any[]): Observable<any> {
    return this.http.put<any>('/api/admin/v1/ai/routing', routes);
  }
  aiUsage(hours = 24): Observable<AiDashboard> {
    return this.http.get<AiDashboard>('/api/admin/v1/ai/usage', { params: { hours } });
  }
  updateAiPolicy(policy: AiPolicy): Observable<AiPolicy> {
    return this.http.patch<AiPolicy>('/api/admin/v1/ai/usage/settings', policy);
  }
  blockPrincipal(principal: string, reason: string): Observable<void> {
    return this.http.post<void>('/api/admin/v1/ai/usage/blocks', { principal, reason });
  }
  unblockPrincipal(principal: string): Observable<void> {
    return this.http.delete<void>(`/api/admin/v1/ai/usage/blocks/${encodeURIComponent(principal)}`);
  }
  adminAudit(): Observable<any[]> {
    return this.http.get<any[]>('/api/admin/v1/security/audit');
  }
}
