import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { catchError, of } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Competition, PlannedSession, StudyPlan, Syllabus } from 'api-client';
import { icons } from '../shared/icons';
import { daysUntil } from '../shared/view-models';

@Component({
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, LucideAngularModule],
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
    return [...grouped.entries()];
  });

  constructor() {
    this.api.competitions().subscribe((items) => {
      this.competitions.set(items);
      const requested = this.route.snapshot.queryParamMap.get('competition');
      this.select(items.find((item) => item.id === requested) ?? items[0] ?? null);
    });
  }
  protected select(competition: Competition | null): void {
    this.competition.set(competition);
    this.plan.set(null);
    this.syllabus.set(null);
    if (!competition) return;
    this.form.patchValue({ examDate: competition.examDate ?? '' });
    this.api
      .plan(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => this.plan.set(value));
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
    this.api.completeSession(competition.id, session.id).subscribe((plan) => {
      this.plan.set(plan);
      this.selectedSession.set(null);
      this.busy.set(false);
    });
  }
}
