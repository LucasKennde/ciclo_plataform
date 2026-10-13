import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiClient, PlannedSession, StudyPlan } from 'api-client';

/**
 * Pomodoro com âncora no servidor, disponível em qualquer tela.
 *
 * <p>Antes o relógio vivia dentro do PlanPage e morria na navegação: o usuário saía do planner,
 * voltava, e o contador tinha sumido — justamente o caso de quem sai do navegador no celular para
 * estudar. Este serviço é root-scoped, então a sessão atravessa troca de tela e o AppShell mostra o
 * atalho mesmo quando o pomodoro foi aberto em outra rota.
 *
 * <p>A invariante do tempo é do servidor: `accumulatedSeconds` é o que já foi investido antes da
 * última pausa, e `startedAt` é quando a execução atual começou. Nada aqui é contador em memória,
 * por isso recarregar a página não devolve tempo.
 */
@Injectable({ providedIn: 'root' })
export class FocusService {
  /**
   * Só o ponteiro fica no navegador: qual sessão estava aberta. O tempo em si nunca sai do
   * servidor. Guardar os dois aqui faria o contador divergir do banco a cada recarregamento.
   */
  private static readonly STORAGE_KEY = 'ciclo.focus.v1';

  private readonly api = inject(ApiClient);
  private competitionId: string | null = null;
  private tick?: ReturnType<typeof setInterval>;
  private readonly now = signal(Date.now());

  readonly session = signal<PlannedSession | null>(null);
  /** Milissegundos restantes; null quando nada está em aberto. */
  readonly remainingMs = signal<number | null>(null);
  readonly running = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  /** Último plano devolvido pelo servidor, para a tela de origem se atualizar. */
  readonly lastPlan = signal<StudyPlan | null>(null);

  readonly started = computed(() => {
    const session = this.session();
    return !!session && (!!session.startedAt || session.status === 'paused');
  });

  readonly paused = computed(() => {
    const session = this.session();
    return !!session && session.status === 'paused' && !session.startedAt;
  });

  readonly finished = computed(() => {
    const remaining = this.remainingMs();
    return remaining !== null && remaining <= 0;
  });

  readonly progress = computed(() => {
    const session = this.session();
    const remaining = this.remainingMs();
    if (!session || remaining === null || session.minutes <= 0) return 0;
    return Math.min(1, Math.max(0, 1 - remaining / (session.minutes * 60_000)));
  });

  /** Abre uma sessão. `competitionId` identifica o concurso para as chamadas de start/pause. */
  open(competitionId: string, session: PlannedSession, remember = true): void {
    this.competitionId = competitionId;
    this.session.set(session);
    this.error.set('');
    this.now.set(Date.now());
    this.syncRemaining();
    if (remember) this.remember(competitionId, session.id);
    // Reabrir uma sessão já em andamento retoma o relógio sem tocar no servidor. O estado precisa
    // acompanhar: deixar `running` falso fazia o botão oferecer "iniciar" sobre um pomodoro que
    // estava contando, e o próximo toque chamava start em vez de pause.
    const emAndamento = !!session.startedAt;
    this.running.set(emAndamento);
    if (emAndamento) this.startTicking();
  }

  close(): void {
    this.stopTicking();
    this.running.set(false);
    this.session.set(null);
    this.remainingMs.set(null);
    this.competitionId = null;
    this.forget();
  }

  /**
   * Recoloca o dock depois de um F5.
   *
   * <p>Quem entra no app no meio de uma sessão é justamente quem estuda em outra aba ou no
   * celular e volta depois — sem isto o pomodoro some e o usuário perde a noção do que estava
   * fazendo. Busca o plano no servidor e reabre: se a sessão foi concluída, apagada ou o plano
   * regerado no meio, some sem erro.
   */
  async restore(): Promise<void> {
    if (this.session()) return;
    const pointer = this.readPointer();
    if (!pointer) return;
    try {
      const plan = await firstValueFrom(this.api.plan(pointer.competitionId));
      const session = plan.sessions.find((item) => item.id === pointer.sessionId);
      if (!session || session.status === 'completed' || session.status === 'missed') {
        this.forget();
        return;
      }
      this.lastPlan.set(plan);
      this.open(pointer.competitionId, session, false);
    } catch {
      // Offline ou servidor fora: o ponteiro fica para a próxima tentativa.
    }
  }

  private remember(competitionId: string, sessionId: string): void {
    try {
      localStorage.setItem(FocusService.STORAGE_KEY, JSON.stringify({ competitionId, sessionId }));
    } catch {
      // Navegador bloqueando storage não pode impedir o pomodoro de funcionar.
    }
  }

  private forget(): void {
    try {
      localStorage.removeItem(FocusService.STORAGE_KEY);
    } catch {
      // idem
    }
  }

  private readPointer(): { competitionId: string; sessionId: string } | null {
    try {
      const raw = localStorage.getItem(FocusService.STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as { competitionId?: string; sessionId?: string };
      if (!parsed?.competitionId || !parsed?.sessionId) return null;
      return { competitionId: parsed.competitionId, sessionId: parsed.sessionId };
    } catch {
      return null;
    }
  }

  async start(): Promise<void> {
    const competitionId = this.competitionId;
    const session = this.session();
    if (!competitionId || !session || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const plan = await firstValueFrom(this.api.startSession(competitionId, session.id));
      this.apply(plan, session.id);
      this.running.set(true);
      this.startTicking();
    } catch {
      this.error.set('Não foi possível iniciar a sessão.');
    } finally {
      this.busy.set(false);
    }
  }

  /** Pausa retoma conforme o estado. A pausa vai ao servidor: localmente seria mentira. */
  async toggle(): Promise<void> {
    const competitionId = this.competitionId;
    const session = this.session();
    if (!competitionId || !session || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      if (this.running()) {
        const plan = await firstValueFrom(this.api.pauseSession(competitionId, session.id));
        this.apply(plan, session.id);
        this.running.set(false);
        this.stopTicking();
      } else {
        const plan = await firstValueFrom(this.api.startSession(competitionId, session.id));
        this.apply(plan, session.id);
        this.running.set(true);
        this.startTicking();
      }
      this.now.set(Date.now());
      this.syncRemaining();
    } catch {
      this.error.set('Não foi possível atualizar o cronômetro.');
    } finally {
      this.busy.set(false);
    }
  }

  async complete(): Promise<StudyPlan | null> {
    const competitionId = this.competitionId;
    const session = this.session();
    if (!competitionId || !session || this.busy()) return null;
    this.busy.set(true);
    this.error.set('');
    try {
      const plan = await firstValueFrom(this.api.completeSession(competitionId, session.id));
      this.lastPlan.set(plan);
      this.apply(plan, session.id);
      this.running.set(false);
      this.stopTicking();
      // Sessão concluída não pode reaparecer no próximo F5.
      this.forget();
      return plan;
    } catch {
      this.error.set('Não foi possível registrar a conclusão.');
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  label(): string {
    const remaining = this.remainingMs();
    if (remaining === null) return '—';
    const total = Math.max(0, Math.round(remaining / 1000));
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
  }

  private apply(plan: StudyPlan, keepSessionId: string): void {
    this.lastPlan.set(plan);
    const refreshed = plan.sessions.find((item) => item.id === keepSessionId);
    if (refreshed) this.session.set(refreshed);
  }

  private startTicking(): void {
    this.stopTicking();
    this.tick = setInterval(() => {
      this.now.set(Date.now());
      this.syncRemaining();
    }, 1000);
  }

  private stopTicking(): void {
    if (this.tick) clearInterval(this.tick);
    this.tick = undefined;
  }

  private syncRemaining(): void {
    const session = this.session();
    if (!session) {
      this.remainingMs.set(null);
      return;
    }
    const accumulated = (session.accumulatedSeconds ?? 0) * 1000;
    if (!session.startedAt) {
      this.remainingMs.set(Math.max(0, session.minutes * 60_000 - accumulated));
      return;
    }
    const elapsed = this.now() - new Date(session.startedAt).getTime();
    this.remainingMs.set(Math.max(0, session.minutes * 60_000 - accumulated - elapsed));
  }
}
