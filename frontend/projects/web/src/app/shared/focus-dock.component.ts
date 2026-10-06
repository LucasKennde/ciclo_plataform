import { Component, computed, inject, output, signal } from '@angular/core';
import { StudyPlan } from 'api-client';
import { LucideAngularModule } from 'lucide-angular';
import { FocusService } from './focus.service';
import { icons } from './icons';

/**
 * O "contador que desce": um pill fixo com o tempo restante que expande para o pomodoro completo.
 *
 * <p>Fica no AppShell, acima de tudo, porque o pomodoro sobrevive à navegação — se o usuário sai do
 * planner para outra tela, o atalho continua ali. No celular ele fica acima da barra de abas e acima
 * da safe-area do iPhone, senão o indicador do sistema tapa o relógio.
 */
@Component({
  selector: 'app-focus-dock',
  standalone: true,
  imports: [LucideAngularModule],
  template: `
    @if (focus.session(); as session) {
      <div class="pointer-events-none fixed inset-x-0 z-50 flex justify-center px-4">
        <div
          class="pointer-events-auto w-full max-w-md"
          [style.marginBottom.px]="collapsed() ? dockOffset() : 'auto'"
        >
          @if (collapsed()) {
            <button
              type="button"
              (click)="expand()"
              class="flex w-full items-center gap-3 rounded-2xl bg-primary px-4 py-3 text-left text-white shadow-xl"
              [attr.aria-label]="
                'Abrir pomodoro de ' + session.topicName + ', ' + focus.label() + ' restantes'
              "
            >
              <span
                class="relative grid h-10 w-10 shrink-0 place-items-center rounded-full"
                [style.background]="ring()"
                aria-hidden="true"
              >
                <span class="grid h-8 w-8 place-items-center rounded-full bg-primary">
                  @if (focus.finished()) {
                    <lucide-icon [img]="icons.Check" [size]="15" />
                  }
                </span>
              </span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-xs font-bold opacity-70">
                  {{ session.subjectName }}
                </span>
                <span class="block truncate text-sm font-extrabold">{{ session.topicName }}</span>
              </span>
              <span class="shrink-0 font-mono text-lg font-extrabold tabular-nums">
                {{ focus.label() }}
              </span>
              <lucide-icon [img]="icons.ChevronUp" [size]="16" class="shrink-0 opacity-70" />
            </button>
          } @else {
            <section
              class="rounded-3xl border border-line bg-white p-5 shadow-2xl"
              role="region"
              aria-label="Pomodoro em andamento"
            >
              <header class="flex items-start justify-between gap-3">
                <div class="min-w-0">
                  <p class="text-[10px] font-extrabold tracking-widest text-faint">
                    {{ focus.paused() ? 'PAUSADO' : 'EM FOCO' }}
                  </p>
                  <h2 class="mt-1 truncate text-base font-extrabold">{{ session.topicName }}</h2>
                  <p class="truncate text-xs text-muted">{{ session.subjectName }}</p>
                </div>
                <button
                  type="button"
                  (click)="collapse()"
                  class="grid h-9 w-9 shrink-0 place-items-center rounded-xl text-muted hover:bg-bg"
                  aria-label="Minimizar pomodoro"
                >
                  <lucide-icon [img]="icons.ChevronDown" [size]="18" />
                </button>
              </header>

              <div class="mt-4 flex items-center gap-4">
                <div
                  class="relative grid h-24 w-24 shrink-0 place-items-center rounded-full"
                  [style.background]="ring()"
                  role="timer"
                  [attr.aria-label]="'Tempo restante: ' + focus.label()"
                >
                  <span
                    class="grid h-[4.5rem] w-[4.5rem] place-items-center rounded-full bg-white font-mono text-xl font-extrabold tabular-nums"
                  >
                    {{ focus.label() }}
                  </span>
                </div>
                <div class="grid flex-1 gap-2">
                  @if (!focus.started()) {
                    <button
                      type="button"
                      (click)="focus.start()"
                      [disabled]="focus.busy()"
                      class="rounded-xl bg-primary px-4 py-3 font-extrabold text-white disabled:opacity-40"
                    >
                      Iniciar {{ session.minutes }} min
                    </button>
                  } @else {
                    <div class="grid grid-cols-2 gap-2">
                      <button
                        type="button"
                        (click)="focus.toggle()"
                        [disabled]="focus.busy()"
                        class="rounded-xl border border-line px-3 py-2.5 text-sm font-extrabold disabled:opacity-50"
                      >
                        {{ focus.running() ? 'Pausar' : 'Continuar' }}
                      </button>
                      <button
                        type="button"
                        (click)="finish()"
                        [disabled]="focus.busy()"
                        class="rounded-xl bg-primary px-3 py-2.5 text-sm font-extrabold text-white disabled:opacity-40"
                      >
                        Concluir
                      </button>
                    </div>
                  }
                </div>
              </div>

              @if (focus.finished()) {
                <p class="mt-3 text-xs font-bold text-coral">
                  Tempo encerrado. Registre a conclusão quando terminar a revisão.
                </p>
              }
              @if (focus.error()) {
                <p class="mt-3 text-xs font-bold text-danger">{{ focus.error() }}</p>
              }
            </section>
          }
        </div>
      </div>
    }
  `,
})
export class FocusDockComponent {
  protected readonly icons = icons;
  protected readonly focus = inject(FocusService);
  protected readonly collapsed = signal(true);
  /**
   * Avisa quem quiser reagir. Quem depende disso é o PlanPage, que observa lastPlan() — assim
   * concluir pelo dock atualiza a linha do tempo sem acoplamento entre os dois.
   */
  readonly completed = output<StudyPlan | null>();
  private readonly offset = signal(80);

  protected readonly dockOffset = computed(() => this.offset());

  constructor() {
    // Mede a barra de abas e a safe-area reais em vez de adivinhar um pixel: no iPhone com
    // indicador de home, bottom fixo sem env(safe-area-inset-bottom) fica atrás do sistema.
    const bar = document.querySelector('nav.fixed.inset-x-0.bottom-0');
    const height = bar instanceof HTMLElement ? bar.offsetHeight : 56;
    const styles = getComputedStyle(document.documentElement);
    const safe = parseFloat(styles.getPropertyValue('--focus-safe-area')) || 0;
    this.offset.set(height + safe + 16);
  }

  protected async finish(): Promise<void> {
    this.completed.emit(await this.focus.complete());
    this.collapse();
  }

  protected ring(): string {
    const deg = this.focus.progress() * 360;
    return `conic-gradient(#ffffff ${deg}deg, rgba(255,255,255,0.25) 0deg)`;
  }

  expand(): void {
    this.collapsed.set(false);
  }

  collapse(): void {
    this.collapsed.set(true);
  }
}
