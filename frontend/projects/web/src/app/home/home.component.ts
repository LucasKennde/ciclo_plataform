import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, of, switchMap, takeWhile, timer } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { DialogComponent } from 'ui';
import {
  ApiClient,
  Competition,
  CompetitionDocument,
  OnboardingState,
  ProcessingJob,
  Progress,
  Simulation,
  StudyPlan,
  Syllabus,
} from 'api-client';
import { AuthService } from 'auth';
import { daysUntil, firstName, todaySessions } from '../shared/view-models';
import { icons } from '../shared/icons';

@Component({
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, RouterLink, LucideAngularModule, DialogComponent],
  templateUrl: './home.component.html',
})
export class HomePage {
  private readonly api = inject(ApiClient);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  protected readonly icons = icons;
  protected readonly loading = signal(true);
  protected readonly competitions = signal<Competition[]>([]);
  protected readonly current = signal<Competition | null>(null);
  protected readonly plan = signal<StudyPlan | null>(null);
  protected readonly syllabus = signal<Syllabus | null>(null);
  protected readonly simulations = signal<Simulation[]>([]);
  protected readonly progress = signal<Progress | null>(null);
  protected readonly documents = signal<CompetitionDocument[]>([]);
  protected readonly onboarding = signal<OnboardingState | null>(null);
  protected readonly onboardingDone = computed(
    () => this.onboarding()?.steps.filter((step) => step.status === 'DONE').length ?? 0,
  );
  protected readonly createOpen = signal(false);
  protected readonly documentOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly processing = signal<ProcessingJob | null>(null);
  protected readonly message = signal('');
  protected readonly createForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.minLength(3)]],
    role: ['', Validators.required],
    board: ['', Validators.required],
    examDate: [''],
  });
  protected readonly name = computed(() => firstName(this.auth.user()?.email ?? ''));
  protected readonly today = computed(() => todaySessions(this.plan()));
  protected readonly completedToday = computed(() =>
    this.today().filter((item) => item.status === 'completed'),
  );
  protected readonly todayMinutes = computed(() =>
    this.completedToday().reduce((sum, item) => sum + item.minutes, 0),
  );
  protected readonly goalMinutes = computed(
    () =>
      this.plan()?.availability.find((slot) => slot.weekday === new Date().getDay())?.minutes ??
      120,
  );
  protected readonly todayPercent = computed(() =>
    Math.min(100, Math.round((this.todayMinutes() / this.goalMinutes()) * 100)),
  );
  protected readonly nextSession = computed(
    () => this.plan()?.sessions.find((item) => item.status === 'planned') ?? null,
  );
  protected readonly examDays = computed(() => daysUntil(this.current()?.examDate ?? null));
  protected readonly planPercent = computed(() => {
    const sessions = this.plan()?.sessions ?? [];
    return sessions.length
      ? Math.round(
          (sessions.filter((item) => item.status === 'completed').length / sessions.length) * 100,
        )
      : 0;
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    forkJoin({
      competitions: this.api.competitions(),
      progress: this.api.progress(),
      simulations: this.api.simulations(),
      onboarding:
        this.auth.user()?.role === 'STUDENT'
          ? this.api.onboarding().pipe(catchError(() => of(null)))
          : of(null),
    }).subscribe({
      next: ({ competitions, progress, simulations, onboarding }) => {
        this.competitions.set(competitions);
        this.progress.set(progress);
        this.simulations.set(simulations);
        this.onboarding.set(onboarding);
        this.current.set(competitions[0] ?? null);
        this.loadCurrent();
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected select(competition: Competition): void {
    this.current.set(competition);
    this.loadCurrent();
  }
  private loadCurrent(): void {
    const competition = this.current();
    if (!competition) return;
    this.api
      .plan(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => this.plan.set(value));
    this.api
      .syllabus(competition.id)
      .pipe(catchError(() => of(null)))
      .subscribe((value) => this.syllabus.set(value));
  }

  protected create(): void {
    if (this.createForm.invalid) return;
    this.busy.set(true);
    this.api.createCompetition(this.createForm.getRawValue()).subscribe({
      next: (created) => {
        this.createOpen.set(false);
        this.busy.set(false);
        this.createForm.reset();
        this.load();
        this.current.set(created);
      },
      error: () => this.busy.set(false),
    });
  }
  protected remove(competition: Competition): void {
    if (confirm(`Excluir ${competition.title}?`))
      this.api.deleteCompetition(competition.id).subscribe(() => this.load());
  }
  protected openDocuments(competition: Competition): void {
    this.current.set(competition);
    this.documentOpen.set(true);
    this.refreshDocuments();
  }
  private refreshDocuments(): void {
    const competition = this.current();
    if (competition)
      this.api.documents(competition.id).subscribe((items) => this.documents.set(items));
  }
  protected upload(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const competition = this.current();
    if (!file || !competition) return;
    this.busy.set(true);
    this.api.uploadDocument(competition.id, file).subscribe({
      next: (result) => {
        this.watch(result.jobId);
        this.refreshDocuments();
      },
      error: () => this.busy.set(false),
    });
  }
  protected reprocess(document: CompetitionDocument): void {
    const competition = this.current();
    if (competition)
      this.api
        .reprocessDocument(competition.id, document.id)
        .subscribe((result) => this.watch(result.jobId));
  }
  private watch(jobId: string): void {
    timer(0, 1500)
      .pipe(
        switchMap(() => this.api.job(jobId)),
        takeWhile((job) => !['COMPLETED', 'FAILED'].includes(job.status), true),
      )
      .subscribe((job) => {
        this.processing.set(job);
        if (['COMPLETED', 'FAILED'].includes(job.status)) {
          this.busy.set(false);
          this.loadCurrent();
          this.refreshDocuments();
          this.load();
        }
      });
  }
  protected approve(): void {
    const competition = this.current();
    if (competition)
      this.api.approveSyllabus(competition.id).subscribe((value) => {
        this.syllabus.set(value);
        this.message.set('Conteúdo aprovado. Agora gere seu plano.');
        this.load();
      });
  }
  protected start(sessionId: string): void {
    const competition = this.current();
    if (competition)
      this.router.navigate(['/app/plano'], {
        queryParams: { session: sessionId, competition: competition.id },
      });
  }

  protected resumeOnboarding(): void {
    this.api
      .updateOnboarding({
        dismissed: false,
        competitionId: this.onboarding()?.competitionId,
      })
      .subscribe(() => this.router.navigateByUrl('/app/primeiros-passos'));
  }
}
