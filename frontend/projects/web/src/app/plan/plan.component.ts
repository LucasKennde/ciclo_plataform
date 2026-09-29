import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { catchError, of } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { DialogComponent } from 'ui';
import { ApiClient, Competition, PlannedSession, StudyPlan, Syllabus } from 'api-client';
import { icons } from '../shared/icons';
import { daysUntil } from '../shared/view-models';

function clock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

@Component({
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, LucideAngularModule, DialogComponent],
  templateUrl: './plan.component.html',
})
export class PlanPage {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  protected readonly icons = icons;
  protected readonly competitions = signal<Competition[]>([]);
  protected readonly competition = signal<Competition | null>(null);
  protected readonly syllabus = signal<Syllabus | null>(null);
  protected readonly plan = signal<StudyPlan | null>(null);
  protected readonly selectedSession = signal<PlannedSession | null>(null);
  protected readonly generateOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly form = this.fb.nonNullable.group({
    examDate: ['', Validators.required],
    availability: this.fb.array(
      [1, 2, 3, 4, 5].map((weekday) => this.fb.nonNullable.group({ weekday, minutes: 90 })),
    ),
  });
  protected get availability(): FormArray {
    return this.form.controls.availability;
  }
  protected readonly completed = computed(
    () => this.plan()?.sessions.filter((item) => item.status === 'completed').length ?? 0,
  );
  protected readonly totalMinutes = computed(
    () => this.plan()?.sessions.reduce((sum, item) => sum + item.minutes, 0) ?? 0,
  );
  protected readonly progress = computed(() =>
    this.plan()?.sessions.length
      ? Math.round((this.completed() / this.plan()!.sessions.length) * 100)
      : 0,
  );
  protected readonly examDays = computed(() => daysUntil(this.competition()?.examDate ?? null));
  protected readonly subjects = computed(() => {
    const rows = new Map<string, { name: string; total: number; done: number; minutes: number }>();
    for (const session of this.plan()?.sessions ?? []) {
      const item = rows.get(session.subjectName) ?? {
        name: session.subjectName,
        total: 0,
        done: 0,
        minutes: 0,
      };
      item.total++;
      item.minutes += session.minutes;
      if (session.status === 'completed') item.done++;
      rows.set(session.subjectName, item);
    }
    return [...rows.values()];
  });
  protected readonly groups = computed(() => {
    const grouped = new Map<string, PlannedSession[]>();
    for (const session of this.plan()?.sessions ?? []) {
      grouped.set(session.date, [...(grouped.get(session.date) ?? []), session]);
    }
    return [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b));
  });

  /**
   * Disciplinas do edital, inclusive as que o plano não conseguiu agendar. Antes a lista vinha só
   * das sessões, então uma disciplina sem nenhuma simplesmente sumia da tela — foi assim que
   * Conhecimentos Específicos desapareceu sem ninguém perceber.
   */
  protected readonly subjectRows = computed(() => {
    const rows = new Map<
      string,
      { name: string; total: number; done: number; minutes: number; scheduled: boolean }
    >();
    for (const subject of this.syllabus()?.subjects ?? [])
      rows.set(subject.name, {
        name: subject.name,
        total: 0,
        done: 0,
        minutes: 0,
        scheduled: false,
      });
    for (const session of this.plan()?.sessions ?? []) {
      const row = rows.get(session.subjectName) ?? {
        name: session.subjectName,
        total: 0,
        done: 0,
        minutes: 0,
        scheduled: false,
      };
      row.total++;
      row.minutes += session.minutes;
      if (session.status === 'completed') row.done++;
      row.scheduled = true;
      rows.set(session.subjectName, row);
    }
    return [...rows.values()].sort(
      (a, b) => Number(b.scheduled) - Number(a.scheduled) || a.name.localeCompare(b.name),
    );
  });

  /** Tópicos do edital que não caberam até a prova — o resto do plano é absorvível, isso não. */
  protected readonly coverage = computed(() => {
    const syllabus = this.syllabus();
    const plan = this.plan();
    if (!syllabus || !plan) return null;
    let total = 0;
    const walk = (nodes: (typeof syllabus.subjects)[number]['topics']): void => {
      for (const node of nodes) {
        total++;
        walk(node.children ?? []);
      }
    };
    for (const subject of syllabus.subjects) walk(subject.topics ?? []);
    const scheduled = new Set(plan.sessions.map((session) => session.topicId));
    return { total, scheduled: scheduled.size, missing: Math.max(0, total - scheduled.size) };
  });

  // --- pomodoro -------------------------------------------------------------------------------

  /** Milissegundos restantes; null quando a sessão não começou. */
  protected readonly remainingMs = signal<number | null>(null);
  protected readonly running = signal(false);
  private tick?: ReturnType<typeof setInterval>;
  private readonly now = signal(Date.now());

  constructor() {
    this.api.competitions().subscribe((items) => {
      this.competitions.set(items);
      const requested = this.route.snapshot.queryParamMap.get('competition');
      // "Começar sessão" na home navega com ?session=<id>. Esse parâmetro nunca era lido: o
      // usuário clicava em começar e chegava no planner sem nada aberto.
      const requestedSession = this.route.snapshot.queryParamMap.get('session');
      this.select(
        items.find((item) => item.id === requested) ?? items[0] ?? null,
        requestedSession,
      );
    });
    inject(DestroyRef).onDestroy(() => this.stopTicking());
  }

  /** Abre a sessão vinda da home, se ela existir no plano. */
  private openRequested(plan: StudyPlan | null, sessionId: string | null) {
    if (!plan || !sessionId) return;
    const session = plan.sessions.find((item) => item.id === sessionId);
    if (session) this.selectSession(session);
  }

  protected selectSession(session: PlannedSession) {
    this.selectedSession.set(session);
    this.syncRemaining();
  }

  protected closeSession() {
    this.stopTicking();
    this.running.set(false);
    this.selectedSession.set(null);
  }

  protected start() {
    const competition = this.competition();
    const session = this.selectedSession();
    if (!competition || !session || this.busy()) return;
    this.busy.set(true);
    this.api.startSession(competition.id, session.id).subscribe({
      next: (plan) => {
        this.applyPlan(plan, session.id);
        this.running.set(true);
        // Desenha o relógio já no primeiro quadro; esperar o tick deixava 1s de anel vazio.
        this.now.set(Date.now());
        this.syncRemaining();
        this.startTicking();
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  protected togglePause() {
    if (this.running()) {
      this.running.set(false);
      this.stopTicking();
    } else {
      this.running.set(true);
      // O relógio desconta do startedAt do servidor, então retomar não dá tempo de volta.
      this.now.set(Date.now());
      this.syncRemaining();
      this.startTicking();
    }
  }

  private startTicking() {
    this.stopTicking();
    this.tick = setInterval(() => {
      this.now.set(Date.now());
      this.syncRemaining();
    }, 1000);
  }

  private stopTicking() {
    if (this.tick) clearInterval(this.tick);
    this.tick = undefined;
  }

  /** Reconta a partir de startedAt, não de um contador em memória: sobrevive a F5 e a troca de aba. */
  private syncRemaining() {
    const session = this.selectedSession();
    if (!session?.startedAt) {
      this.remainingMs.set(null);
      return;
    }
    const elapsed = this.now() - new Date(session.startedAt).getTime();
    this.remainingMs.set(Math.max(0, session.minutes * 60_000 - elapsed));
  }

  protected elapsedLabel(): string {
    const remaining = this.remainingMs();
    if (remaining === null) return clock(this.minutesOfSession() * 60_000);
    return clock(remaining);
  }

  protected finishedFocus(): boolean {
    const remaining = this.remainingMs();
    return remaining !== null && remaining <= 0;
  }

  /** Fração 0..1 para o anel de progresso. */
  protected focusProgress(): number {
    const total = this.minutesOfSession() * 60_000;
    const remaining = this.remainingMs();
    if (total <= 0) return 0;
    if (remaining === null) return 0;
    return Math.min(1, Math.max(0, 1 - remaining / total));
  }

  private minutesOfSession(): number {
    return this.selectedSession()?.minutes ?? 0;
  }

  protected kindLabel(kind: string): string {
    return kind === 'review' ? 'Revisão' : kind === 'questions' ? 'Questões' : 'Estudo';
  }

  protected applyPlan(plan: StudyPlan | null, keepSessionId?: string) {
    this.plan.set(plan);
    if (!keepSessionId) return;
    const refreshed = plan?.sessions.find((item) => item.id === keepSessionId);
    if (refreshed) this.selectedSession.set(refreshed);
  }

  protected select(competition: Competition | null, sessionId: string | null = null): void {
    this.competition.set(competition);
    this.plan.set(null);
    this.syllabus.set(null);
    this.closeSession();
    if (!competition) return;
    this.form.patchValue({ examDate: competition.examDate ?? '' });
    this.api
      .plan(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => {
        this.plan.set(value);
        this.openRequested(value, sessionId);
      });
    this.api
      .syllabus(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => this.syllabus.set(value));
  }
  protected selectById(id: string): void {
    this.select(this.competitions().find((competition) => competition.id === id) ?? null);
  }
  protected generate(): void {
    const competition = this.competition();
    if (!competition || this.form.invalid) return;
    this.busy.set(true);
    const value = this.form.getRawValue();
    this.api.generatePlan(competition.id, value).subscribe({
      next: (plan) => {
        this.plan.set(plan);
        this.generateOpen.set(false);
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }
  protected complete(session: PlannedSession): void {
    const competition = this.competition();
    if (!competition) return;
    this.busy.set(true);
    this.api.completeSession(competition.id, session.id).subscribe({
      next: (plan) => {
        this.applyPlan(plan);
        this.closeSession();
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }
}
