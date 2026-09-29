import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, ElementRef, inject, input, output } from '@angular/core';

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Diálogo modal acessível, reaproveitado por todos os overlays do produto.
 *
 * <p>Os overlays anteriores eram um `div` com (click)="fechar()": sem role="dialog", sem
 * aria-modal, sem aria-labelledby, sem foco preso, sem Escape e sem devolução do foco. Um leitor de
 * tela tratava o conteúdo como texto solto na página e o Tab escapava para o fundo.
 */
@Component({
  selector: 'ciclo-dialog',
  standalone: true,
  host: {
    class: 'fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/45 p-4',
    '(click)': 'onBackdrop($event)',
  },
  template: `
    <section
      role="dialog"
      aria-modal="true"
      [attr.aria-labelledby]="labelId"
      (keydown.escape)="dismiss.emit()"
      (click)="$event.stopPropagation()"
      class="my-auto w-full rounded-3xl bg-white shadow-2xl outline-none"
      [class]="panelClass()"
    >
      <ng-content />
    </section>
  `,
})
export class DialogComponent {
  private readonly element = inject(ElementRef<HTMLElement>);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly previouslyFocused = this.document.activeElement as HTMLElement | null;
  private readonly uid = `ciclo-dialog-${++sequence}`;

  readonly labelId = `${this.uid}-label`;
  /** Classe extra no painel: largura máxima, altura máxima, rolagem. */
  readonly panelClass = input('max-w-2xl');
  readonly dismiss = output<void>();

  constructor() {
    this.document.body.style.overflow = 'hidden';
    this.document.addEventListener('keydown', this.trapFocus);
    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('keydown', this.trapFocus);
      this.document.body.style.overflow = '';
      this.previouslyFocused?.focus?.();
    });
    // Depois do primeiro render o conteúdo projetado já existe e pode receber foco.
    setTimeout(() => this.focusFirst(), 0);
  }

  private panel(): HTMLElement | null {
    return (this.element.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[role="dialog"]',
    );
  }

  private focusFirst() {
    if (!(this.element.nativeElement as HTMLElement).isConnected) return;
    const panel = this.panel();
    if (!panel) return;
    const target = panel.querySelector<HTMLElement>('[data-autofocus]') ?? panel;
    (target.matches(FOCUSABLE)
      ? target
      : (panel.querySelector<HTMLElement>(FOCUSABLE) ?? panel)
    ).focus();
  }

  private readonly trapFocus = (event: KeyboardEvent) => {
    if (event.key !== 'Tab') return;
    const panel = this.panel();
    const focusable = panel ? [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)] : [];
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && this.document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && this.document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  onBackdrop(event: MouseEvent) {
    if (event.target === event.currentTarget) this.dismiss.emit();
  }
}

let sequence = 0;
