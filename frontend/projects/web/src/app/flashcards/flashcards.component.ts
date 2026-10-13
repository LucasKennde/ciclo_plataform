import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient, Competition, Flashcard } from 'api-client';
import { icons } from '../shared/icons';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, LucideAngularModule],
  templateUrl: './flashcards.component.html',
})
export class FlashcardsPage {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder);
  protected readonly icons = icons;
  protected readonly competitions = signal<Competition[]>([]);
  protected readonly cards = signal<Flashcard[]>([]);
  protected readonly filter = signal('');
  protected readonly createOpen = signal(false);
  protected readonly studyOpen = signal(false);
  protected readonly index = signal(0);
  protected readonly flipped = signal(false);
  protected readonly form = this.fb.nonNullable.group({
    competitionId: [''],
    front: ['', Validators.required],
    back: ['', Validators.required],
  });
  protected readonly filtered = computed(() =>
    this.filter()
      ? this.cards().filter((card) => card.competitionId === this.filter())
      : this.cards(),
  );
  protected readonly card = computed(() => this.filtered()[this.index()] ?? null);
  /** Barra de progresso: quanto falta, não só em que número estamos. */
  protected readonly progress = computed(() => {
    const total = this.filtered().length;
    return total ? ((this.index() + 1) / total) * 100 : 0;
  });
  protected flip(): void {
    this.flipped.update((value) => !value);
  }
  protected closeStudy(): void {
    this.studyOpen.set(false);
    this.flipped.set(false);
  }
  /**
   * Teclado é o caminho natural de revisão: um card por tecla, sem tirar a mão do teclado. Espaço
   * vira o card, setas navegam, Esc fecha.
   */
  @HostListener('document:keydown', ['$event'])
  protected onKey(event: KeyboardEvent): void {
    if (!this.studyOpen()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closeStudy();
      return;
    }
    const alvo = event.target as HTMLElement | null;
    // Não sequestrar a barra de espaço de quem está digitando num campo do formulário.
    if (alvo && /^(INPUT|TEXTAREA|SELECT)$/.test(alvo.tagName)) return;
    if (event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault();
      this.flip();
      return;
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.next(1);
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.next(-1);
    }
  }
  constructor() {
    this.api.competitions().subscribe((items) => this.competitions.set(items));
    this.load();
  }
  private load(): void {
    this.api.flashcards().subscribe((items) => this.cards.set(items));
  }
  protected create(): void {
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    this.api
      .createFlashcard({ ...value, competitionId: value.competitionId || null })
      .subscribe(() => {
        this.createOpen.set(false);
        this.form.reset();
        this.load();
      });
  }
  protected remove(id: string): void {
    this.api.deleteFlashcard(id).subscribe(() => this.load());
  }
  protected study(): void {
    this.index.set(0);
    this.flipped.set(false);
    this.studyOpen.set(true);
  }
  protected next(delta: number): void {
    const length = this.filtered().length;
    if (!length) return;
    this.index.set((this.index() + delta + length) % length);
    this.flipped.set(false);
  }
}
