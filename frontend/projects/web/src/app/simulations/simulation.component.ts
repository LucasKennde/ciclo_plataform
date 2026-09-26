import { Component, computed, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Question, Simulation } from 'api-client';
import { icons } from '../shared/icons';
import { accuracy } from '../shared/view-models';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, LucideAngularModule],
  templateUrl: './simulation.component.html',
})
export class SimulationPage {
  private readonly api = inject(ApiClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  protected readonly icons = icons;
  protected readonly simulation = signal<Simulation | null>(null);
  protected readonly questions = signal<Question[]>([]);
  protected readonly index = signal(0);
  protected readonly selected = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly editing = signal(false);
  protected readonly titleForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(160)]],
  });
  protected readonly question = computed(() => this.questions()[this.index()] ?? null);
  protected readonly item = computed(
    () => this.simulation()?.items.find((item) => item.questionId === this.question()?.id) ?? null,
  );
  protected readonly answered = computed(
    () => this.simulation()?.items.filter((item) => item.selectedIndex !== null).length ?? 0,
  );
  protected readonly score = computed(() => accuracy(this.simulation()!));
  constructor() {
    this.load();
  }
  private load(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    forkJoin({
      simulation: this.api.simulation(id),
      questions: this.api.simulationQuestions(id),
    }).subscribe(({ simulation, questions }) => {
      this.simulation.set(simulation);
      this.questions.set(questions);
      this.titleForm.patchValue({ title: simulation.title || 'Simulado' });
      const first = simulation.items.findIndex((item) => item.selectedIndex === null);
      this.index.set(first >= 0 ? first : 0);
      this.syncSelection();
    });
  }
  private syncSelection(): void {
    this.selected.set(this.item()?.selectedIndex ?? null);
  }
  protected choose(value: number): void {
    if (this.simulation()?.status === 'FINISHED' || this.item()?.selectedIndex !== null) return;
    this.selected.set(value);
  }
  protected answer(): void {
    const simulation = this.simulation();
    const question = this.question();
    const selected = this.selected();
    if (!simulation || !question || selected === null) return;
    this.busy.set(true);
    this.api.answerSimulation(simulation.id, question.id, selected).subscribe((updated) => {
      this.simulation.set(updated);
      this.busy.set(false);
      if (this.index() < this.questions().length - 1) {
        this.index.update((value) => value + 1);
        this.syncSelection();
      }
    });
  }
  protected move(value: number): void {
    this.index.set(value);
    this.syncSelection();
  }
  protected finish(): void {
    const simulation = this.simulation();
    if (simulation)
      this.api.finishSimulation(simulation.id).subscribe((updated) => this.simulation.set(updated));
  }
  protected rename(): void {
    const simulation = this.simulation();
    if (!simulation || this.titleForm.invalid) return;
    this.api
      .renameSimulation(simulation.id, this.titleForm.getRawValue().title)
      .subscribe((updated) => {
        this.simulation.set(updated);
        this.editing.set(false);
      });
  }
  protected done(): void {
    void this.router.navigateByUrl('/app/simulados');
  }
}
