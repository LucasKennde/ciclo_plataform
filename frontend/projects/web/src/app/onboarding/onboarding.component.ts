import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import {
  ApiClient,
  Competition,
  OnboardingState,
  ProcessingJob,
  ProgramOption,
  ProgramSource,
  Syllabus,
  SyllabusSubject,
} from 'api-client';
import { icons } from '../shared/icons';

function uploadErrorMessage(error: unknown): string {
  const fallback = 'Não foi possível enviar o edital agora. Tente novamente em instantes.';
  if (!(error instanceof HttpErrorResponse)) return fallback;
  if (error.status === 413) return 'O PDF é maior que o limite de 30 MB.';
  if (error.status === 422 && typeof error.error?.message === 'string') return error.error.message;
  if (error.status === 401 || error.status === 403)
    return 'Sua sessão expirou. Recarregue a página e entre novamente.';
  return fallback;
}

/**
 * O usuário não pode agir sobre um código de política ou sobre a mensagem crua do provedor. O
 * errorCode vira uma frase acionável e a mensagem do backend fica como detalhe — é ela que diz, por
 * exemplo, que a resposta da IA foi cortada no limite de tokens.
 */
const JOB_FAILURES: Record<string, string> = {
  PROVIDER_ERROR: 'A IA não conseguiu ler este edital. Tente novamente em instantes.',
  AI_ROUTE_MISSING:
    'Nenhum modelo de IA está configurado para extrair o edital. Peça ao administrador para configurar em Configurações → IA.',
  MANUAL_BLOCK: 'Este processamento foi bloqueado. Fale com o suporte.',
  KILL_SWITCH: 'O processamento por IA está pausado no momento. Tente novamente mais tarde.',
  PER_MINUTE: 'Você atingiu o limite de pedidos por minuto. Tente novamente em instantes.',
  PER_HOUR: 'Você atingiu o limite de pedidos por hora. Tente mais tarde.',
  PER_DAY: 'Você atingiu o limite diário de pedidos por IA.',
  PRINCIPAL_TOKENS_DAY: 'Você atingiu o limite diário de tokens por IA.',
  GLOBAL_PER_MINUTE: 'A plataforma está no limite de pedidos por minuto. Tente em instantes.',
  GLOBAL_TOKENS_DAY: 'A plataforma atingiu o limite diário de tokens por IA.',
};

const JOB_STATUS: Record<string, string> = {
  QUEUED: 'Na fila…',
  COMPLETED: 'Concluído',
  FAILED: 'Falhou',
};

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, LucideAngularModule],
  templateUrl: './onboarding.component.html',
})
export class OnboardingPage implements OnDestroy {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private processingSubscription?: Subscription;
  protected readonly icons = icons;
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  /**
   * Falha do job de IA em um sinal separado de `error`: `load()` limpa `error`, e sem esta separação
   * o motivo da falha sumiria da tela assim que recarregássemos o estado para voltar à etapa do
   * edital.
   */
  protected readonly jobError = signal('');
  protected readonly state = signal<OnboardingState | null>(null);
  protected readonly competition = signal<Competition | null>(null);
  protected readonly syllabus = signal<Syllabus | null>(null);
  /** Edital oficial transcrito no backend: entra sem PDF e sem depender de IA. */
  protected readonly programs = signal<ProgramOption[]>([]);
  protected readonly programSource = signal<ProgramSource | null>(null);
  protected readonly chosenProgram = signal('');
  protected readonly draftSubjects = signal<SyllabusSubject[]>([]);
  protected readonly processing = signal<ProcessingJob | null>(null);
  protected readonly competitionForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.minLength(3)]],
    role: ['', Validators.required],
    board: ['', Validators.required],
    examDate: ['', Validators.required],
  });
  protected readonly planForm = this.fb.nonNullable.group({
    examDate: ['', Validators.required],
    availability: this.fb.array(
      [
        { weekday: 1, minutes: 90 },
        { weekday: 2, minutes: 90 },
        { weekday: 3, minutes: 90 },
        { weekday: 4, minutes: 90 },
        { weekday: 5, minutes: 90 },
        { weekday: 6, minutes: 0 },
        { weekday: 7, minutes: 0 },
      ].map((slot) =>
        this.fb.nonNullable.group({
          weekday: slot.weekday,
          minutes: [slot.minutes, [Validators.required, Validators.min(0), Validators.max(720)]],
        }),
      ),
    ),
  });
  protected readonly minimumDate = tomorrow();
  protected readonly completedSteps = computed(
    () => this.state()?.steps.filter((step) => step.status === 'DONE').length ?? 0,
  );
  protected readonly progressPercent = computed(() => {
    const total = this.state()?.steps.length ?? 5;
    return Math.round((this.completedSteps() / Math.max(total - 1, 1)) * 100);
  });

  constructor() {
    this.load();
  }

  ngOnDestroy(): void {
    this.processingSubscription?.unsubscribe();
  }

  protected get availability(): FormArray {
    return this.planForm.controls.availability;
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.onboarding().subscribe({
      next: (state) => {
        this.state.set(state);
        this.processing.set(state.processingJob);
        if (!state.competitionId) {
          this.competition.set(null);
          this.loading.set(false);
          return;
        }
        this.api.competition(state.competitionId).subscribe({
          next: (competition) => {
            this.competition.set(competition);
            const examDate = competition.examDate ?? '';
            this.planForm.patchValue({ examDate });
            this.loading.set(false);
            this.loadPrograms();
            if (state.currentStep === 'REVIEW') this.loadSyllabus(competition.id);
            if (state.currentStep === 'PROCESSING' && state.processingJob) {
              this.watch(state.processingJob.id);
            }
          },
          error: () => this.fail('Não foi possível carregar o concurso selecionado.'),
        });
      },
      error: () => this.fail('Não foi possível carregar seus primeiros passos.'),
    });
  }

  protected createCompetition(): void {
    if (this.competitionForm.invalid) return;
    this.busy.set(true);
    this.error.set('');
    this.api
      .createCompetition(this.competitionForm.getRawValue())
      .pipe(
        switchMap((competition) => {
          this.competition.set(competition);
          return this.api.updateOnboarding({ dismissed: false, competitionId: competition.id });
        }),
      )
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.load();
        },
        error: () => this.fail('Não foi possível cadastrar o concurso. Confira os campos.'),
      });
  }

  protected upload(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const competition = this.competition();
    if (!file || !competition) return;
    this.busy.set(true);
    this.error.set('');
    this.jobError.set('');
    this.api.uploadDocument(competition.id, file).subscribe({
      next: ({ jobId }) => this.watch(jobId),
      error: (error: unknown) => this.fail(uploadErrorMessage(error)),
    });
  }

  /** Sugere o cargo digitado, mas só quando a correspondência é exata: errar aqui semeia o edital
   * errado inteiro, então na dúvida o usuário escolhe. */
  protected suggestedProgram(): string {
    const role = this.competition()?.role ?? '';
    return this.programs().find((p) => p.name === role)?.slug ?? '';
  }

  protected loadPrograms(): void {
    if (this.programs().length) return;
    this.api.programs().subscribe({
      next: ({ programs, source }) => {
        this.programs.set(programs);
        this.programSource.set(source);
        this.chosenProgram.set(this.suggestedProgram());
      },
      error: () => this.programs.set([]),
    });
  }

  protected applyProgram(): void {
    const competition = this.competition();
    const slug = this.chosenProgram();
    if (!competition || !slug) return;
    this.busy.set(true);
    this.error.set('');
    this.api.applyProgram(competition.id, slug).subscribe({
      next: () => this.loadSyllabus(competition.id),
      error: (error: unknown) => this.fail(uploadErrorMessage(error)),
    });
  }

  protected retry(): void {
    const competition = this.competition();
    const processing = this.processing();
    if (!competition || !processing?.aggregateId) return;
    this.busy.set(true);
    this.error.set('');
    this.jobError.set('');
    this.api.reprocessDocument(competition.id, processing.aggregateId).subscribe({
      next: ({ jobId }) => {
        this.busy.set(false);
        this.watch(jobId);
      },
      error: () =>
        this.fail('Não foi possível reprocessar o edital. Tente enviar o PDF novamente.'),
    });
  }

  protected renameSubject(index: number, value: string): void {
    this.draftSubjects.update((subjects) =>
      subjects.map((subject, current) =>
        current === index ? { ...subject, name: value } : subject,
      ),
    );
  }

  protected renameTopic(subjectIndex: number, topicIndex: number, value: string): void {
    this.draftSubjects.update((subjects) =>
      subjects.map((subject, currentSubject) =>
        currentSubject === subjectIndex
          ? {
              ...subject,
              topics: subject.topics.map((topic, currentTopic) =>
                currentTopic === topicIndex ? { ...topic, name: value } : topic,
              ),
            }
          : subject,
      ),
    );
  }

  protected removeTopic(subjectIndex: number, topicIndex: number): void {
    this.draftSubjects.update((subjects) =>
      subjects.map((subject, currentSubject) =>
        currentSubject === subjectIndex
          ? {
              ...subject,
              topics: subject.topics.filter((_, currentTopic) => currentTopic !== topicIndex),
            }
          : subject,
      ),
    );
  }

  protected approve(): void {
    const competition = this.competition();
    if (!competition || !this.validDraft()) return;
    this.busy.set(true);
    this.error.set('');
    this.api
      .reviseSyllabus(competition.id, this.draftSubjects())
      .pipe(switchMap(() => this.api.approveSyllabus(competition.id)))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.load();
        },
        error: () => this.fail('Não foi possível aprovar o conteúdo. Revise os campos.'),
      });
  }

  protected generatePlan(): void {
    const competition = this.competition();
    if (!competition || this.planForm.invalid || !this.hasStudyTime()) return;
    this.busy.set(true);
    this.error.set('');
    this.api.generatePlan(competition.id, this.planForm.getRawValue()).subscribe({
      next: () => {
        this.busy.set(false);
        this.load();
      },
      error: () => this.fail('Não foi possível gerar o plano. Confira a data e sua rotina.'),
    });
  }

  protected skip(): void {
    this.busy.set(true);
    this.api
      .updateOnboarding({
        dismissed: true,
        competitionId: this.competition()?.id ?? this.state()?.competitionId,
      })
      .subscribe({
        next: () => this.router.navigateByUrl('/app'),
        error: () => this.fail('Não foi possível salvar seu progresso.'),
      });
  }

  protected finish(): void {
    this.router.navigateByUrl('/app');
  }

  protected hasStudyTime(): boolean {
    return this.planForm.getRawValue().availability.some((slot) => slot.minutes >= 25);
  }

  protected validDraft(): boolean {
    return (
      this.draftSubjects().length > 0 &&
      this.draftSubjects().every(
        (subject) =>
          subject.name.trim().length > 0 &&
          subject.topics.length > 0 &&
          subject.topics.every((topic) => topic.name.trim().length > 0),
      )
    );
  }

  private loadSyllabus(competitionId: string): void {
    this.api.syllabus(competitionId).subscribe({
      next: (syllabus) => {
        this.syllabus.set(syllabus);
        this.draftSubjects.set(structuredClone(syllabus.subjects));
      },
      error: () => this.fail('Não foi possível carregar o conteúdo do edital.'),
    });
  }

  private watch(jobId: string): void {
    this.processingSubscription?.unsubscribe();
    this.busy.set(true);
    this.jobError.set('');
    this.processingSubscription = timer(0, 1500)
      .pipe(
        switchMap(() => this.api.job(jobId)),
        takeWhile((job) => !['COMPLETED', 'FAILED'].includes(job.status), true),
      )
      .subscribe({
        next: (job) => {
          this.processing.set(job);
          if (job.status === 'COMPLETED') {
            this.busy.set(false);
            this.load();
          }
          if (job.status === 'FAILED') {
            this.busy.set(false);
            this.jobError.set(this.explain(job));
            // Sem este load() a tela ficava presa no painel "Organizando seu conteúdo…" com o
            // status cru FAILED, e o botão "Tentar novamente" — que só existe na etapa DOCUMENT —
            // nunca aparecia. O backend devolve currentStep=DOCUMENT para job falho, então é
            // reload que tira o usuário do beco sem refazer upload.
            this.load();
          }
        },
        error: () => this.fail('Perdemos a conexão com o processamento. Tente novamente.'),
      });
  }

  /** Frase acionável para o usuário; a mensagem do provedor vira detalhe menor. */
  private explain(job: ProcessingJob): string {
    const known = job.errorCode ? JOB_FAILURES[job.errorCode] : undefined;
    const headline = known ?? 'Não foi possível processar o edital.';
    // Com código desconhecido a mensagem do backend é a única informação que existe — escondê-la
    // deixaria o usuário sem nenhuma pista.
    const detail = job.errorMessage;
    return detail && detail !== headline ? `${headline} (${detail})` : headline;
  }

  protected statusLabel(status?: string | null): string {
    if (!status) return 'Processando…';
    return JOB_STATUS[status] ?? status;
  }

  private fail(message: string): void {
    this.busy.set(false);
    this.loading.set(false);
    this.error.set(message);
  }
}

function tomorrow(): string {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
