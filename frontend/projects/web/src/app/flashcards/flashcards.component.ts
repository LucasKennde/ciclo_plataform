import { Component, computed, inject, signal } from '@angular/core';
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
