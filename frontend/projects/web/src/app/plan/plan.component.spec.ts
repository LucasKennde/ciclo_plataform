import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiClient, Competition, PlannedSession, StudyPlan, Syllabus } from 'api-client';
import { FocusService } from '../shared/focus.service';
import { PlanPage } from './plan.component';

const competition: Competition = {
  id: 'competition-id',
  workspaceId: 'workspace-id',
  title: 'SEDUC',
  role: 'Professor',
  board: 'Cebraspe',
  examDate: '2027-10-10',
  status: 'DRAFT',
  createdAt: '2026-09-25T00:00:00Z',
  updatedAt: '2026-09-25T00:00:00Z',
};

function session(over: Partial<PlannedSession> = {}): PlannedSession {
  return {
    id: 'session-1',
    topicId: 'topic-1',
    subjectName: 'Conhecimentos Específicos - Matemática',
    topicName: 'Geometria analítica',
    date: '2026-09-30',
    minutes: 50,
    kind: 'study',
    status: 'planned',
    startedAt: null,
    accumulatedSeconds: 0,
    ...over,
  };
}

const plan: StudyPlan = {
  id: 'plan-id',
  competitionId: competition.id,
  sessions: [session()],
  availability: [],
};

function syllabus(subjects: unknown[]): Syllabus {
  return {
    id: 's',
    competitionId: competition.id,
    version: 1,
    status: 'APPROVED',
    subjects,
  } as unknown as Syllabus;
}

const twoSubjects = syllabus([
  { id: 'mat', name: 'Conhecimentos Específicos - Matemática', weight: 3, topics: [] },
  { id: 'port', name: 'Língua Portuguesa', weight: 2, topics: [] },
]);

describe('PlanPage', () => {
  const api = {
    competitions: vi.fn(),
    plan: vi.fn(),
    syllabus: vi.fn(),
    generatePlan: vi.fn(),
    startSession: vi.fn(),
    pauseSession: vi.fn(),
    completeSession: vi.fn(),
  };

  const build = (
    queryParams: Record<string, string> = {},
    current = plan,
    content = twoSubjects,
  ) => {
    api.competitions.mockReturnValue(of([competition]));
    api.plan.mockReturnValue(of(current));
    api.syllabus.mockReturnValue(of(content));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    return TestBed.runInInjectionContext(
      () => TestBed.createComponent(PlanPage).componentInstance,
    ) as any;
  };

  beforeEach(() => vi.clearAllMocks());

  // "Começar sessão" na home navega com ?session=<id>. Esse parâmetro nunca era lido: o usuário
  // clicava em começar e o planner abria sem nada.
  it('abre a sessão vinda da query param da home', () => {
    const page = build({ competition: competition.id, session: 'session-1' });

    expect(page.focus.session()?.id).toBe('session-1');
  });

  it('ignora a query param quando a sessão não existe no plano', () => {
    const page = build({ competition: competition.id, session: 'inexistente' });

    expect(page.focus.session()).toBeNull();
  });

  it('grava o início no servidor e passa a contar o tempo', async () => {
    const page = build({ competition: competition.id, session: 'session-1' });
    const startedAt = new Date().toISOString();
    api.startSession.mockReturnValue(
      of({ ...plan, sessions: [session({ status: 'in_progress', startedAt })] }),
    );

    await page.focus.start();

    expect(api.startSession).toHaveBeenCalledWith(competition.id, 'session-1');
    expect(page.focus.running()).toBe(true);
    expect(page.focus.remainingMs()).toBeGreaterThan(49 * 60_000);
    page.focus.close();
  });

  it('zera o tempo quando o cronômetro vence', () => {
    const old = new Date(Date.now() - 51 * 60_000).toISOString();
    const stale = { ...plan, sessions: [session({ status: 'in_progress', startedAt: old })] };
    const page = build({ competition: competition.id, session: 'session-1' }, stale);

    expect(page.focus.finished()).toBe(true);
    expect(page.focus.remainingMs()).toBe(0);
    expect(page.focus.progress()).toBe(1);
    page.focus.close();
  });

  // Pausar precisa ir para o servidor. Se ficasse só no setInterval, o startedAt antigo continuaria
  // valendo e ao recarregar a página o relógio voltaria a correr: a pausa seria mentira.
  it('pausa no servidor e preserva o tempo investido', async () => {
    const page = build({ competition: competition.id, session: 'session-1' });
    api.startSession.mockReturnValue(
      of({
        ...plan,
        sessions: [session({ status: 'in_progress', startedAt: '2026-09-30T10:00:00Z' })],
      }),
    );
    api.pauseSession.mockReturnValue(
      of({
        ...plan,
        sessions: [session({ status: 'paused', startedAt: null, accumulatedSeconds: 420 })],
      }),
    );
    await page.focus.start();

    await page.focus.toggle();

    expect(api.pauseSession).toHaveBeenCalledWith(competition.id, 'session-1');
    expect(page.focus.running()).toBe(false);
    // O servidor bankou os 7 min decorridos: 420s acumulados, 50 - 7 = 43 min restantes.
    expect(page.focus.remainingMs()).toBe(43 * 60_000);
    page.focus.close();
  });

  it('ao recarregar uma sessão pausada, mostra o que restou e não zera', () => {
    const paused = {
      ...plan,
      sessions: [session({ status: 'paused', startedAt: null, accumulatedSeconds: 1200 })],
    };
    const page = build({ competition: competition.id, session: 'session-1' }, paused);

    // 20 min investidos de 50. Antes, sem accumulatedSeconds, isso voltava a 50:00.
    expect(page.focus.remainingMs()).toBe(30 * 60 * 1000);
    expect(page.focus.started()).toBe(true);
    expect(page.focus.paused()).toBe(true);
    page.focus.close();
  });

  it('retomar uma sessão pausada não devolve o tempo investido', async () => {
    const paused = {
      ...plan,
      sessions: [session({ status: 'paused', startedAt: null, accumulatedSeconds: 900 })],
    };
    const page = build({ competition: competition.id, session: 'session-1' }, paused);
    api.startSession.mockReturnValue(
      of({
        ...plan,
        sessions: [
          session({
            status: 'in_progress',
            startedAt: new Date().toISOString(),
            accumulatedSeconds: 900,
          }),
        ],
      }),
    );

    await page.focus.toggle();

    expect(api.startSession).toHaveBeenCalledWith(competition.id, 'session-1');
    expect(page.focus.running()).toBe(true);
    expect(page.focus.remainingMs()).toBeLessThan(50 * 60 * 1000);
    page.focus.close();
  });

  it('mostra disciplina do edital que ficou sem sessão, em vez de sumir', () => {
    // O plano só tem sessão de Matemática; Língua Portuguesa está no edital e não foi agendada.
    const page = build({ competition: competition.id });

    const rows = page.subjectRows() as { name: string; scheduled: boolean }[];
    expect(rows.map((r) => r.name)).toEqual(
      expect.arrayContaining(['Conhecimentos Específicos - Matemática', 'Língua Portuguesa']),
    );
    expect(rows.find((r) => r.name === 'Língua Portuguesa')?.scheduled).toBe(false);
  });

  it('conta sessões perdidas fora do total e no contador separado', () => {
    const withMissed = {
      ...plan,
      sessions: [
        session({ id: 'a', status: 'completed' }),
        session({ id: 'b', status: 'missed', subjectName: 'Língua Portuguesa' }),
        session({ id: 'c', status: 'planned' }),
      ],
    };
    const page = build({ competition: competition.id }, withMissed, twoSubjects);

    // 1 de 2 agendadas: a perdida não pode entrar no denominador.
    expect(page.progress()).toBe(50);
    expect(page.missed()).toBe(1);
    expect(page.scheduled()).toBe(2);
    const row = (page.subjectRows() as { name: string; missed: number }[]).find(
      (r) => r.name === 'Língua Portuguesa',
    );
    expect(row?.missed).toBe(1);
  });

  it('avisa quantos tópicos do edital não couberam até a prova', () => {
    const content = syllabus([
      {
        id: 'mat',
        name: 'Matemática',
        weight: 1,
        topics: [
          { id: 'topic-1', name: 'A', weight: 1, evidence: null, children: [] },
          { id: 'topic-2', name: 'B', weight: 1, evidence: null, children: [] },
        ],
      },
    ]);
    const page = build({ competition: competition.id }, plan, content);

    const cover = page.coverage();
    expect(cover.total).toBe(2);
    expect(cover.scheduled).toBe(1);
    expect(cover.missing).toBe(1);
  });

  it('traduz o tipo da sessão em vez de mostrar o enum em inglês', () => {
    const page = build({ competition: competition.id });

    expect(page.kindLabel('review')).toBe('Revisão');
    expect(page.kindLabel('study')).toBe('Estudo');
    expect(page.kindLabel('questions')).toBe('Questões');
  });

  it('ordena a linha do tempo por data', () => {
    const unordered = {
      ...plan,
      sessions: [
        session({ id: 'b', date: '2026-10-05' }),
        session({ id: 'a', date: '2026-09-30' }),
      ],
    };
    const page = build({ competition: competition.id }, unordered);

    expect((page.groups() as [string, unknown][]).map(([date]) => date)).toEqual([
      '2026-09-30',
      '2026-10-05',
    ]);
  });

  it('atualiza a linha do tempo quando o pomodoro é concluído pelo dock global', async () => {
    const page = build({ competition: competition.id, session: 'session-1' });
    const done = session({ id: 'session-1', status: 'completed', accumulatedSeconds: 3000 });
    api.completeSession.mockReturnValue(of({ ...plan, sessions: [done] }));
    TestBed.inject(FocusService);

    await page.focus.complete();
    TestBed.tick();

    expect(page.plan().sessions[0].status).toBe('completed');
    page.focus.close();
  });
});
