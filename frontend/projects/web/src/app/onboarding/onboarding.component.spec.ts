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
    reprocessDocument: vi.fn(),
    job: vi.fn(),
    reviseSyllabus: vi.fn(),
    approveSyllabus: vi.fn(),
    generatePlan: vi.fn(),
    programs: vi.fn(),
    applyProgram: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    api.onboarding.mockReturnValue(of(initial));
    api.programs.mockReturnValue(
      of({
        programs: [
          {
            slug: 'professor-matematica',
            name: 'Professor de Matemática',
            topicos: 19,
            subtopicos: 41,
            weight: 50,
          },
          {
            slug: 'professor-biologia',
            name: 'Professor de Biologia',
            topicos: 17,
            subtopicos: 53,
            weight: 50,
          },
        ],
        source: {
          orgao: 'Seduc',
          edital: 'Edital nº 014/2026',
          publicacao: 'DOE 14/08/2026',
          anexo: 'Anexo III',
          questoesP1: 30,
          questoesP2: 50,
          transcricao: 'Literal do Anexo III.',
        },
      }),
    );
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

  describe('edital oficial do catálogo', () => {
    const started = (role: string) => {
      api.onboarding.mockReturnValue(
        of({
          ...initial,
          status: 'IN_PROGRESS',
          currentStep: 'DOCUMENT',
          competitionId: competition.id,
        }),
      );
      api.competition.mockReturnValue(of({ ...competition, role }));
      const fixture = TestBed.createComponent(OnboardingPage);
      fixture.detectChanges();
      return fixture.componentInstance as any;
    };

    it('carrega o catálogo assim que existe concurso', () => {
      const component = started('Professor de Matemática');

      expect(api.programs).toHaveBeenCalled();
      expect(component.programs().length).toBe(2);
    });

    it('escolhe o cargo sozinho quando bate exatamente com o que foi digitado', () => {
      // Digitou o nome oficial do cargo: não faz sentido obrigar a escolher de novo.
      expect(started('Professor de Matemática').chosenProgram()).toBe('professor-matematica');
    });

    it('não escolhe quando o cargo digitado não bate com nenhum programa', () => {
      // Cargo genérico: chutar aqui semearia o edital inteiro errado.
      expect(started('Auditor').chosenProgram()).toBe('');
    });

    it('aplica o programa escolhido e recarrega o rascunho para revisão', () => {
      const component = started('Professor de Biologia');
      api.applyProgram.mockReturnValue(
        of({
          id: 'syllabus-id',
          competitionId: competition.id,
          status: 'DRAFT',
          subjects: [],
        } as any),
      );
      api.syllabus.mockReturnValue(
        of({
          id: 'syllabus-id',
          competitionId: competition.id,
          status: 'DRAFT',
          subjects: [],
        } as any),
      );
      component.chosenProgram.set('professor-biologia');

      component.applyProgram();

      expect(api.applyProgram).toHaveBeenCalledWith(competition.id, 'professor-biologia');
      expect(api.syllabus).toHaveBeenCalledWith(competition.id);
    });

    it('não aplica nada sem programa escolhido', () => {
      const component = started('Auditor');

      component.applyProgram();

      expect(api.applyProgram).not.toHaveBeenCalled();
    });
  });

  describe('a falha da IA no processamento do edital', () => {
    const failedJob = (errorCode: string | null, errorMessage: string | null) => ({
      id: 'job-id',
      aggregateId: 'document-id',
      type: 'SYLLABUS_EXTRACTION',
      status: 'FAILED',
      progress: 0,
      errorCode,
      errorMessage,
    });

    // Estado que o backend devolve para um job que falhou: a etapa volta a ser DOCUMENT, que é
    // onde o botão de reprocessar mora.
    const failedState: OnboardingState = {
      ...initial,
      status: 'IN_PROGRESS',
      currentStep: 'DOCUMENT',
      competitionId: competition.id,
      processingJob: failedJob('PROVIDER_ERROR', 'A IA não conseguiu ler este edital.'),
    };

    // timer(0, 1500) emite de forma assíncrona: sem esperar o tick o job ainda não chegou.
    const runUntilFailed = async (job: ReturnType<typeof failedJob>) => {
      api.job.mockReturnValue(of(job));
      // load() refaz onboarding + competition; sem o mock do competition o subscribe estoura.
      api.competition.mockReturnValue(of(competition));
      // Vale para todas as chamadas: o construtor também faz um load() e é o reload pós-falha que
      // precisa devolver a etapa DOCUMENT.
      const onboarding = vi.spyOn(api, 'onboarding').mockReturnValue(of(failedState));
      const fixture = TestBed.createComponent(OnboardingPage);
      const component = fixture.componentInstance as any;
      component.competition.set(competition);
      component.processingSubscription?.unsubscribe();
      component['watch']('job-id');
      await new Promise((resolve) => setTimeout(resolve, 0));
      return { component, onboarding };
    };

    // Antes, FAILED apenas escrevia em error() e a tela ficava presa no painel "Organizando seu
    // conteúdo…" com o status cru, sem botão de retry: o usuário não tinha como sair dali.
    it('volta para a etapa do edital ao falhar, em vez de travar na de processamento', async () => {
      const { component, onboarding } = await runUntilFailed(
        failedJob('PROVIDER_ERROR', 'A resposta da IA foi cortada no limite de tokens.'),
      );

      // Uma chamada vem do construtor, a segunda do reload que a falha dispara.
      expect(onboarding).toHaveBeenCalledTimes(2);
      expect(component.state().currentStep).toBe('DOCUMENT');
      expect(component.busy()).toBe(false);
    });

    it('traduz o errorCode em uma frase acionável e mantém o detalhe do provedor', async () => {
      const { component } = await runUntilFailed(
        failedJob('PROVIDER_ERROR', 'A resposta da IA foi cortada no limite de 32000 tokens.'),
      );

      expect(component.jobError()).toContain('A IA não conseguiu ler este edital');
      expect(component.jobError()).toContain('32000 tokens');
    });

    it('aponta a configuração do admin quando não há rota de IA', async () => {
      const { component } = await runUntilFailed(
        failedJob('AI_ROUTE_MISSING', 'Nenhuma rota configurada para SYLLABUS_EXTRACTION'),
      );

      expect(component.jobError()).toContain('Configurações → IA');
    });

    it('não mostra o código cru quando o erro é desconhecido', async () => {
      const { component } = await runUntilFailed(failedJob('CODIGO_NOVO', 'detalhe do backend'));

      expect(component.jobError()).toBe(
        'Não foi possível processar o edital. (detalhe do backend)',
      );
    });

    it('limpa a falha anterior quando o usuário tenta de novo', async () => {
      const { component } = await runUntilFailed(failedJob('PROVIDER_ERROR', 'x'));
      expect(component.jobError()).not.toBe('');
      api.reprocessDocument.mockReturnValue(
        of({ documentId: 'document-id', jobId: 'job-2', statusUrl: '' }),
      );
      api.job.mockReturnValue(
        of({ ...failedJob('PROVIDER_ERROR', 'x'), status: 'QUEUED', progress: 0 }),
      );

      component.retry();

      expect(component.jobError()).toBe('');
      expect(api.reprocessDocument).toHaveBeenCalledWith(competition.id, 'document-id');
    });

    it('traduz o status interno para um rótulo legível', () => {
      const fixture = TestBed.createComponent(OnboardingPage);
      const component = fixture.componentInstance as any;

      expect(component.statusLabel('QUEUED')).toBe('Na fila…');
      expect(component.statusLabel('COMPLETED')).toBe('Concluído');
      expect(component.statusLabel('FAILED')).toBe('Falhou');
      expect(component.statusLabel(null)).toBe('Processando…');
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
