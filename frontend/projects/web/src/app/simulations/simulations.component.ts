import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Competition, MockExamSource, Question, Simulation, Syllabus } from 'api-client';
import { accuracy } from '../shared/view-models';
import { icons } from '../shared/icons';

@Component({
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, RouterLink, LucideAngularModule],
  templateUrl: './simulations.component.html',
})
export class SimulationsPage {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  protected readonly icons = icons;
  protected readonly Math = Math;
  protected readonly competitions = signal<Competition[]>([]);
  protected readonly competition = signal<Competition | null>(null);
  protected readonly simulations = signal<Simulation[]>([]);
  protected readonly syllabus = signal<Syllabus | null>(null);
  protected readonly sources = signal<MockExamSource[]>([]);
  protected readonly review = signal<Question[]>([]);
  protected readonly createOpen = signal(false);
  protected readonly uploadOpen = signal(false);
  protected readonly reviewOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly form = this.fb.nonNullable.group({
    subjectId: [''],
    difficulty: [3, [Validators.min(1), Validators.max(5)]],
    count: [10, [Validators.min(1), Validators.max(20)]],
  });
  protected readonly finished = computed(() =>
    this.simulations().filter((item) => item.status === 'FINISHED'),
  );
  protected readonly active = computed(() =>
    this.simulations().filter((item) => item.status === 'IN_PROGRESS'),
  );
  protected readonly average = computed(() =>
    this.finished().length
      ? this.finished().reduce((sum, item) => sum + accuracy(item), 0) / this.finished().length
      : 0,
  );
  protected readonly totalQuestions = computed(() =>
    this.finished().reduce((total, simulation) => total + simulation.items.length, 0),
  );
  protected readonly accuracy = accuracy;
  constructor() {
    this.api.competitions().subscribe((items) => {
      this.competitions.set(items);
      this.select(items[0] ?? null);
    });
  }
  protected select(competition: Competition | null): void {
    this.competition.set(competition);
    if (!competition) return;
    forkJoin({
      simulations: this.api.simulations(competition.id),
      sources: this.api.mockExams(competition.id),
    }).subscribe(({ simulations, sources }) => {
      this.simulations.set(simulations);
      this.sources.set(sources);
    });
    this.api.syllabus(competition.id).subscribe({
      next: (value) => this.syllabus.set(value),
      error: () => this.syllabus.set(null),
    });
  }
  protected selectById(id: string): void {
    this.select(this.competitions().find((competition) => competition.id === id) ?? null);
  }
  protected start(): void {
    const competition = this.competition();
    if (!competition) return;
    this.busy.set(true);
    this.api.startSimulation(competition.id, this.form.getRawValue()).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.createOpen.set(false);
        if (result.simulation) void this.router.navigate(['/app/simulado', result.simulation.id]);
        else this.select(competition);
      },
      error: () => this.busy.set(false),
    });
  }
  protected upload(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const competition = this.competition();
    if (!file || !competition) return;
    this.busy.set(true);
    this.api.uploadMockExam(competition.id, file).subscribe({
      next: () => {
        this.busy.set(false);
        this.uploadOpen.set(false);
        this.select(competition);
      },
      error: () => this.busy.set(false),
    });
  }
  protected openReview(source: MockExamSource): void {
    this.api.mockExamQuestions(source.id).subscribe((questions) => {
      this.review.set(questions);
      this.reviewOpen.set(true);
    });
  }
  protected decide(question: Question, decision: 'publish' | 'reject'): void {
    this.api
      .reviewQuestion(question.id, decision)
      .subscribe((updated) =>
        this.review.update((items) =>
          items.map((item) => (item.id === updated.id ? updated : item)),
        ),
      );
  }
  protected classify(): void {
    const competition = this.competition();
    if (competition) this.api.classifyMockExamQuestions(competition.id).subscribe();
  }
}
