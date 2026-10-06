import { Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { catchError, of } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { FocusService } from '../shared/focus.service';
import { ApiClient, Competition, PlannedSession, StudyPlan, Syllabus } from 'api-client';
import { icons } from '../shared/icons';
import { daysUntil } from '../shared/view-models';

function clock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

@Component({
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, LucideAngularModule],
  templateUrl: './plan.component.html',
})
export class PlanPage {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  protected readonly focus = inject(FocusService);
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
  protected readonly scheduled = computed(
    () => this.plan()?.sessions.filter((item) => item.status !== 'missed').length ?? 0,
  );
  protected readonly missed = computed(
    () => this.plan()?.sessions.filter((item) => item.status === 'missed').length ?? 0,
  );
  protected readonly totalMinutes = computed(
    () => this.plan()?.sessions.reduce((sum, item) => sum + item.minutes, 0) ?? 0,
  );
  protected readonly progress = computed(() =>
    this.scheduled() ? Math.round((this.completed() / this.scheduled()) * 100) : 0,
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
      {
        name: string;
        total: number;
        done: number;
        missed: number;
        minutes: number;
        scheduled: boolean;
      }
    >();
    for (const subject of this.syllabus()?.subjects ?? [])
      rows.set(subject.name, {
        name: subject.name,
        total: 0,
        done: 0,
        missed: 0,
        minutes: 0,
        scheduled: false,
      });
    for (const session of this.plan()?.sessions ?? []) {
      const row = rows.get(session.subjectName) ?? {
        name: session.subjectName,
        total: 0,
        done: 0,
        missed: 0,
        minutes: 0,
        scheduled: false,
      };
      // Sessão não feita não conta como pendente: entrar no total faria o progresso nunca chegar
      // a 100% sem o usuário saber o que falta.
      if (session.status !== 'missed') {
        row.total++;
        row.minutes += session.minutes;
      }
      if (session.status === 'completed') row.done++;
      if (session.status === 'missed') row.missed++;
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

  // --- pomodoro --------------------------------------------------------------------------------
  // A lógica vive no FocusService (root-scoped) para sobreviver à navegação; aqui só abrimos a
  // sessão e reagimos quando ela for concluída.

  protected selectSession(session: PlannedSession): void {
    const competition = this.competition();
    if (competition) this.focus.open(competition.id, session);
  }

  /**
   * "Começar sessão" na home navega para /app/plano?session=<id>. Esse parâmetro nunca era lido:
   * o usuário clicava em começar e o planner abria sem nada.
   */
  private openRequestedSession(plan: StudyPlan | null, sessionId: string | null): void {
    if (!plan || !sessionId) return;
    const session = plan.sessions.find((item) => item.id === sessionId);
    if (session) this.selectSession(session);
  }

  protected closeSession(): void {
    this.focus.close();
  }

  protected kindLabel(kind: string): string {
    return kind === 'review' ? 'Revisão' : kind === 'questions' ? 'Questões' : 'Estudo';
  }

  constructor() {
    // Concluir pelo dock global atualiza a linha do tempo sem o dock conhecer o planner.
    effect(() => {
      const latest = this.focus.lastPlan();
      if (latest && latest.competitionId === this.competition()?.id) this.plan.set(latest);
    });
    this.api.competitions().subscribe((items) => {
      this.competitions.set(items);
      const requested = this.route.snapshot.queryParamMap.get('competition');
      const requestedSession = this.route.snapshot.queryParamMap.get('session');
      this.select(
        items.find((item) => item.id === requested) ?? items[0] ?? null,
        requestedSession,
      );
    });
  }

  protected select(competition: Competition | null, sessionId: string | null = null): void {
    this.competition.set(competition);
    this.plan.set(null);
    this.syllabus.set(null);
    this.focus.close();
    if (!competition) return;
    this.form.patchValue({ examDate: competition.examDate ?? '' });
    this.api
      .plan(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => {
        this.plan.set(value);
        this.openRequestedSession(value, sessionId);
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
        this.plan.set(plan);
        this.focus.close();
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }
}
