import { Component, computed, inject, signal } from '@angular/core';
import { forkJoin, of, switchMap } from 'rxjs';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Competition, Progress, Simulation, StudyPlan } from 'api-client';
import { icons } from '../shared/icons';

@Component({
  standalone: true,
  imports: [LucideAngularModule],
  templateUrl: './progress.component.html',
})
export class ProgressPage {
  private readonly api = inject(ApiClient);
  protected readonly icons = icons;
  protected readonly Math = Math;
  protected readonly data = signal<Progress | null>(null);
  protected readonly plans = signal<StudyPlan[]>([]);
  protected readonly simulations = signal<Simulation[]>([]);
  protected readonly levelProgress = computed(() => ((this.data()?.xp ?? 0) % 300) / 3);
  protected readonly week = computed(() => {
    const result = Array.from({ length: 7 }, (_, index) => {
      const date = new Date();
      date.setDate(date.getDate() - (6 - index));
      const key = date.toISOString().slice(0, 10);
      const minutes = this.plans()
        .flatMap((plan) => plan.sessions)
        .filter((session) => session.status === 'completed' && session.date === key)
        .reduce((sum, session) => sum + session.minutes, 0);
      return { label: ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'][date.getDay()], minutes };
    });
    return result;
  });
  protected readonly maxMinutes = computed(() =>
    Math.max(60, ...this.week().map((day) => day.minutes)),
  );
  protected readonly subjects = computed(() => {
    const map = new Map<string, { total: number; done: number }>();
    for (const session of this.plans().flatMap((plan) => plan.sessions)) {
      const item = map.get(session.subjectName) ?? { total: 0, done: 0 };
      item.total++;
      if (session.status === 'completed') item.done++;
      map.set(session.subjectName, item);
    }
    return [...map]
      .map(([name, value]) => ({
        name,
        percent: value.total ? Math.round((value.done / value.total) * 100) : 0,
      }))
      .sort((a, b) => b.percent - a.percent);
  });
  protected readonly badges = computed(() => {
    const data = this.data();
    return [
      {
        icon: '🎯',
        name: 'Primeiro passo',
        unlocked: this.simulations().filter((s) => s.status === 'FINISHED').length > 0,
      },
      { icon: '📚', name: 'Plano em ação', unlocked: (data?.completedSessions ?? 0) > 0 },
      { icon: '🔥', name: 'Constância', unlocked: (data?.streak ?? 0) >= 3 },
      { icon: '🏆', name: 'Expert', unlocked: (data?.level ?? 1) >= 4 },
    ];
  });
  constructor() {
    forkJoin({
      data: this.api.progress(),
      simulations: this.api.simulations(),
      competitions: this.api.competitions(),
    })
      .pipe(
        switchMap(({ data, simulations, competitions }) => {
          this.data.set(data);
          this.simulations.set(simulations);
          return competitions.length
            ? forkJoin(
                competitions.map((competition: Competition) => this.api.plan(competition.id)),
              )
            : of([]);
        }),
      )
      .subscribe({ next: (plans) => this.plans.set(plans), error: () => this.plans.set([]) });
  }
}
