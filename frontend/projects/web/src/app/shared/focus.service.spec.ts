import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiClient, PlannedSession, StudyPlan } from 'api-client';
import { FocusService } from './focus.service';

const competitionId = 'competition-1';

function session(over: Partial<PlannedSession> = {}): PlannedSession {
  return {
    id: 'session-1',
    topicId: 'topic-1',
    subjectName: 'Matemática',
    topicName: 'Geometria',
    date: '2026-09-30',
    minutes: 50,
    kind: 'study',
    status: 'planned',
    startedAt: null,
    accumulatedSeconds: 0,
    ...over,
  };
}

const plan = (sessions: PlannedSession[]): StudyPlan => ({
  id: 'plan-1',
  competitionId,
  sessions,
  availability: [],
});

describe('FocusService', () => {
  const api = {
    startSession: vi.fn(),
    pauseSession: vi.fn(),
    completeSession: vi.fn(),
  };

  const build = () => {
    TestBed.configureTestingModule({
      providers: [FocusService, { provide: ApiClient, useValue: api }],
    });
    return TestBed.inject(FocusService);
  };

  beforeEach(() => vi.clearAllMocks());

  it('abre uma sessão planejada sem mostrar relógio', () => {
    const focus = build();
    focus.open(competitionId, session());

    expect(focus.session()?.id).toBe('session-1');
    expect(focus.started()).toBe(false);
    expect(focus.running()).toBe(false);
    // Ainda não startedAt, então o que importa é o total planejado.
    expect(focus.remainingMs()).toBe(50 * 60_000);
  });

  it('inicia no servidor e passa a contar', async () => {
    const focus = build();
    const startedAt = new Date().toISOString();
    api.startSession.mockReturnValue(of(plan([session({ status: 'in_progress', startedAt })])));
    focus.open(competitionId, session());

    await focus.start();

    expect(api.startSession).toHaveBeenCalledWith(competitionId, 'session-1');
    expect(focus.running()).toBe(true);
    expect(focus.started()).toBe(true);
    expect(focus.remainingMs()).toBeGreaterThan(49 * 60_000);
    focus.close();
  });

  it('pausa no servidor, zerando o startedAt e guardando o tempo', async () => {
    const focus = build();
    api.startSession.mockReturnValue(
      of(plan([session({ status: 'in_progress', startedAt: '2026-09-30T10:00:00Z' })])),
    );
    api.pauseSession.mockReturnValue(
      of(plan([session({ status: 'paused', startedAt: null, accumulatedSeconds: 900 })])),
    );
    focus.open(competitionId, session());
    await focus.start();

    await focus.toggle();

    expect(api.pauseSession).toHaveBeenCalledWith(competitionId, 'session-1');
    expect(focus.running()).toBe(false);
    expect(focus.paused()).toBe(true);
    // 15 min de 50 investidos.
    expect(focus.remainingMs()).toBe(35 * 60_000);
    focus.close();
  });

  it('retomar não devolve o tempo acumulado', async () => {
    const focus = build();
    focus.open(
      competitionId,
      session({ status: 'paused', startedAt: null, accumulatedSeconds: 900 }),
    );
    api.startSession.mockReturnValue(
      of(
        plan([
          session({
            status: 'in_progress',
            startedAt: new Date().toISOString(),
            accumulatedSeconds: 900,
          }),
        ]),
      ),
    );

    await focus.toggle();

    expect(api.startSession).toHaveBeenCalled();
    expect(focus.remainingMs()).toBeLessThan(50 * 60_000);
    focus.close();
  });

  it('reabrir uma sessão já em andamento retoma o relógio sem chamar o servidor', () => {
    const focus = build();
    // É o caso de quem sai para estudar no celular e volta depois: abrir a tela não pode resetar.
    focus.open(
      competitionId,
      session({ status: 'in_progress', startedAt: '2026-09-30T10:00:00Z' }),
    );

    expect(api.startSession).not.toHaveBeenCalled();
    expect(focus.started()).toBe(true);
    expect(focus.remainingMs()).not.toBeNull();
    focus.close();
  });

  it('concluir devolve o plano e para o relógio', async () => {
    const focus = build();
    const done = session({ status: 'completed', startedAt: null, accumulatedSeconds: 3000 });
    api.completeSession.mockReturnValue(of(plan([done])));
    focus.open(competitionId, session());

    const result = await focus.complete();

    expect(result?.sessions[0].status).toBe('completed');
    expect(focus.lastPlan()?.sessions[0].status).toBe('completed');
    expect(focus.running()).toBe(false);
  });

  it('fecha e limpa o estado', () => {
    const focus = build();
    focus.open(competitionId, session());

    focus.close();

    expect(focus.session()).toBeNull();
    expect(focus.remainingMs()).toBeNull();
    expect(focus.running()).toBe(false);
  });

  it('formata o relógio em mm:ss', () => {
    const focus = build();
    focus.open(competitionId, session({ minutes: 50 }));

    expect(focus.label()).toBe('50:00');
    focus.close();
  });

  it('não deixa o estado colado quando a chamada falha', async () => {
    const focus = build();
    api.startSession.mockReturnValue(of(plan([session()])));
    focus.open(competitionId, session());
    api.pauseSession.mockReturnValue({
      subscribe: (observer: { error: (e: unknown) => void }) => {
        observer.error(new Error('offline'));
        return { unsubscribe() {} };
      },
    } as never);
    await focus.start();

    await focus.toggle();

    expect(focus.error()).toBe('Não foi possível atualizar o cronômetro.');
    expect(focus.busy()).toBe(false);
    focus.close();
  });
});
