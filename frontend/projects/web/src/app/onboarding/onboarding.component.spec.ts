import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApiClient, Competition, OnboardingState } from 'api-client';
import { OnboardingPage } from './onboarding.component';

describe('OnboardingPage', () => {
  const initial: OnboardingState = {
    status: 'NOT_STARTED',
    currentStep: 'COMPETITION',
    competitionId: null,
    dismissedAt: null,
    completedAt: null,
    steps: [
      { key: 'COMPETITION', label: 'Concurso', status: 'CURRENT' },
      { key: 'DOCUMENT', label: 'Edital', status: 'PENDING' },
      { key: 'REVIEW', label: 'Conteúdo', status: 'PENDING' },
      { key: 'AVAILABILITY', label: 'Rotina', status: 'PENDING' },
      { key: 'COMPLETED', label: 'Plano pronto', status: 'PENDING' },
    ],
    processingJob: null,
  };
  const competition: Competition = {
    id: 'competition-id',
    workspaceId: 'workspace-id',
    title: 'Receita Federal',
    role: 'Auditor',
    board: 'FGV',
    examDate: '2027-10-10',
    status: 'DRAFT',
    createdAt: '2026-09-25T00:00:00Z',
    updatedAt: '2026-09-25T00:00:00Z',
  };
  const api = {
    onboarding: vi.fn(),
    competition: vi.fn(),
    createCompetition: vi.fn(),
    updateOnboarding: vi.fn(),
    syllabus: vi.fn(),
    uploadDocument: vi.fn(),
    job: vi.fn(),
    reviseSyllabus: vi.fn(),
    approveSyllabus: vi.fn(),
    generatePlan: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    api.onboarding.mockReturnValue(of(initial));
    await TestBed.configureTestingModule({
      imports: [OnboardingPage],
      providers: [provideRouter([]), { provide: ApiClient, useValue: api }],
    }).compileComponents();
  });

  it('creates the competition and associates it with the onboarding', () => {
    api.createCompetition.mockReturnValue(of(competition));
    api.updateOnboarding.mockReturnValue(
      of({ ...initial, status: 'IN_PROGRESS', currentStep: 'DOCUMENT' }),
    );
    const fixture = TestBed.createComponent(OnboardingPage);
    const component = fixture.componentInstance as any;
    component.competitionForm.setValue({
      title: competition.title,
      role: competition.role,
      board: competition.board,
      examDate: competition.examDate,
    });

    component.createCompetition();

    expect(api.createCompetition).toHaveBeenCalledWith({
      title: competition.title,
      role: competition.role,
      board: competition.board,
      examDate: competition.examDate,
    });
    expect(api.updateOnboarding).toHaveBeenCalledWith({
      dismissed: false,
      competitionId: competition.id,
    });
  });

  describe('document upload errors', () => {
    const upload = (response: HttpErrorResponse): string => {
      api.uploadDocument.mockReturnValue(throwError(() => response));
      const fixture = TestBed.createComponent(OnboardingPage);
      const component = fixture.componentInstance as any;
      component.competition.set(competition);
      const file = new File(['%PDF'], 'edital.pdf', { type: 'application/pdf' });

      component.upload({ target: { files: [file] } } as unknown as Event);

      expect(api.uploadDocument).toHaveBeenCalledWith(competition.id, file);
      return component.error();
    };

    it('reports a file above the size limit on 413', () => {
      expect(upload(new HttpErrorResponse({ status: 413 }))).toBe(
        'O PDF é maior que o limite de 30 MB.',
      );
    });

    it('shows the backend message on 422', () => {
      const response = new HttpErrorResponse({
        status: 422,
        error: { code: 'study.invalid', message: 'Envie um arquivo PDF.' },
      });

      expect(upload(response)).toBe('Envie um arquivo PDF.');
    });

    it('asks the user to sign in again on 403', () => {
      expect(upload(new HttpErrorResponse({ status: 403 }))).toBe(
        'Sua sessão expirou. Recarregue a página e entre novamente.',
      );
    });

    it('falls back to a generic message on server errors', () => {
      expect(upload(new HttpErrorResponse({ status: 500 }))).toBe(
        'Não foi possível enviar o edital agora. Tente novamente em instantes.',
      );
    });
  });
});
