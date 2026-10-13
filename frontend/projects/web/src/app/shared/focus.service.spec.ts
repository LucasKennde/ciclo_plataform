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
    plan: vi.fn(),
  };

  const build = () => {
    TestBed.configureTestingModule({
      providers: [FocusService, { provide: ApiClient, useValue: api }],
    });
    return TestBed.inject(FocusService);
  };

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

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
    // O estado precisa dizer "em andamento": senão o botão oferece iniciar sobre um relógio que já
    // está contando, e o próximo toque chamaria start em vez de pause.
    expect(focus.running()).toBe(true);
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

  describe('persistência entre recarregamentos', () => {
    it('reabre a sessão depois do F5 sem tocar no servidor', async () => {
      // É o caso de quem estuda em outra aba e volta: o dock não pode sumir.
      const focus = build();
      focus.open(competitionId, session());
      // navegador "recarregou": serviço novo, mesmo localStorage
      localStorage.setItem(
        'ciclo.focus.v1',
        JSON.stringify({ competitionId, sessionId: 'session-1' }),
      );
      const after = TestBed.runInInjectionContext(() => new FocusService());
      api.plan.mockReturnValue(
        of(plan([session({ status: 'paused', startedAt: null, accumulatedSeconds: 600 })])),
      );

      await after.restore();

      expect(after.session()?.id).toBe('session-1');
      // 10 min investidos de 50: o tempo veio do servidor, não do navegador.
      expect(after.remainingMs()).toBe(40 * 60_000);
      expect(api.startSession).not.toHaveBeenCalled();
      after.close();
    });

    it('retoma a contagem de uma sessão que estava em andamento', async () => {
      const focus = build();
      const pointer = { competitionId, sessionId: 'session-1' };
      localStorage.setItem('ciclo.focus.v1', JSON.stringify(pointer));
      const after = TestBed.runInInjectionContext(() => new FocusService());
      api.plan.mockReturnValue(
        of(plan([session({ status: 'in_progress', startedAt: new Date().toISOString() })])),
      );

      await after.restore();

      expect(after.running()).toBe(true);
      expect(after.remainingMs()).toBeGreaterThan(49 * 60_000);
      after.close();
    });

    it('esquece a sessão se ela foi concluída em outro lugar', async () => {
      const focus = build();
      localStorage.setItem(
        'ciclo.focus.v1',
        JSON.stringify({ competitionId, sessionId: 'session-1' }),
      );
      const after = TestBed.runInInjectionContext(() => new FocusService());
      api.plan.mockReturnValue(of(plan([session({ status: 'completed' })])));

      await after.restore();

      expect(after.session()).toBeNull();
      expect(localStorage.getItem('ciclo.focus.v1')).toBeNull();
    });

    it('aguenta um ponteiro corrompido sem quebrar a tela', async () => {
      const focus = build();
      localStorage.setItem('ciclo.focus.v1', '{nao é json');
      const after = TestBed.runInInjectionContext(() => new FocusService());

      await expect(after.restore()).resolves.toBeUndefined();
      expect(after.session()).toBeNull();
    });

    it('aguenta o servidor fora do ar e tenta de novo depois', async () => {
      const focus = build();
      localStorage.setItem(
        'ciclo.focus.v1',
        JSON.stringify({ competitionId, sessionId: 'session-1' }),
      );
      const after = TestBed.runInInjectionContext(() => new FocusService());
      api.plan.mockReturnValue({
        subscribe: (o: { error: (e: unknown) => void }) => {
          o.error(new Error('offline'));
          return { unsubscribe() {} };
        },
      } as never);

      await after.restore();

      expect(after.session()).toBeNull();
      // Offline não é motivo para apagar: o servidor volta e a sessão é recuperada.
      expect(localStorage.getItem('ciclo.focus.v1')).not.toBeNull();
    });

    it('limpa o ponteiro quando o pomodoro é concluído', async () => {
      const focus = build();
      focus.open(competitionId, session());
      expect(localStorage.getItem('ciclo.focus.v1')).not.toBeNull();
      api.completeSession.mockReturnValue(
        of(plan([session({ status: 'completed', accumulatedSeconds: 3000 })])),
      );

      await focus.complete();

      expect(localStorage.getItem('ciclo.focus.v1')).toBeNull();
    });

    it('não grava o ponteiro ao reabrir durante o restore', async () => {
      const focus = build();
      focus.open(competitionId, session());
      const after = TestBed.runInInjectionContext(() => new FocusService());
      after.open('outro-concurso', session({ id: 'session-2' }), false);

      expect(localStorage.getItem('ciclo.focus.v1')).toContain(competitionId);
      after.close();
    });
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
