import { Component, inject, signal } from '@angular/core';
import { forkJoin, of, switchMap } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Question, Simulation } from 'api-client';
import { icons } from '../shared/icons';

interface ErrorEntry {
  simulation: Simulation;
  question: Question;
  selectedIndex: number;
}

@Component({
  standalone: true,
  imports: [LucideAngularModule],
  templateUrl: './errors.component.html',
})
export class ErrorsPage {
  private readonly api = inject(ApiClient);
  protected readonly icons = icons;
  protected readonly entries = signal<ErrorEntry[]>([]);
  protected readonly current = signal<ErrorEntry | null>(null);
  protected readonly loading = signal(true);
  constructor() {
    this.api
      .simulations()
      .pipe(
        switchMap((simulations) =>
          simulations.length
            ? forkJoin(
                simulations.map((simulation) =>
                  this.api
                    .simulationQuestions(simulation.id)
                    .pipe(switchMap((questions) => of({ simulation, questions }))),
                ),
              )
            : of([]),
        ),
      )
      .subscribe((runs) => {
        const entries: ErrorEntry[] = [];
        for (const run of runs)
          for (const item of run.simulation.items) {
            if (item.selectedIndex !== null && item.selectedIndex !== item.correctIndex) {
              const question = run.questions.find((candidate) => candidate.id === item.questionId);
              if (question)
                entries.push({
                  simulation: run.simulation,
                  question,
                  selectedIndex: item.selectedIndex,
                });
            }
          }
        this.entries.set(entries);
        this.loading.set(false);
      });
  }
}
