import { Component, input } from '@angular/core';

@Component({
  selector: 'ciclo-card',
  standalone: true,
  host: {
    class:
      'block rounded-2xl border p-5 shadow-sm [background:var(--surface)] [border-color:var(--line)]',
  },
  template: '<ng-content />',
})
export class CardComponent {}

@Component({
  selector: 'ciclo-page-header',
  standalone: true,
  template: `<header class="mb-8 flex items-end justify-between gap-4">
    <div>
      <small class="font-extrabold tracking-[.14em] [color:var(--muted)]">{{ eyebrow() }}</small>
      <h1 class="my-1 text-[clamp(2rem,4vw,3rem)] font-bold [font-family:var(--font-serif)]">
        {{ title() }}
      </h1>
      <p class="m-0 [color:var(--muted)]">{{ subtitle() }}</p>
    </div>
    <ng-content />
  </header>`,
})
export class PageHeaderComponent {
  eyebrow = input('');
  title = input.required<string>();
  subtitle = input('');
}

@Component({
  selector: 'ciclo-metric',
  standalone: true,
  host: {
    class:
      'grid gap-1 rounded-2xl border p-5 [background:var(--surface)] [border-color:var(--line)]',
  },
  template: `<div class="text-xs [color:var(--muted)]">{{ label() }}</div>
    <strong class="text-3xl">{{ value() }}</strong
    ><small class="text-xs [color:var(--muted)]">{{ hint() }}</small>`,
})
export class MetricComponent {
  label = input.required<string>();
  value = input.required<string | number>();
  hint = input('');
}
