import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiClient, Flashcard } from 'api-client';
import { FlashcardsPage } from './flashcards.component';

const card = (n: number): Flashcard => ({
  id: `card-${n}`,
  competitionId: null,
  front: `Pergunta ${n}`,
  back: `Gabarito: C) alternativa ${n}`,
  createdAt: '2026-10-01T00:00:00Z',
});

describe('FlashcardsPage', () => {
  const api = {
    competitions: vi.fn(),
    flashcards: vi.fn(),
    createFlashcard: vi.fn(),
    deleteFlashcard: vi.fn(),
  };

  const build = (cards: Flashcard[] = [card(1), card(2), card(3)]) => {
    api.competitions.mockReturnValue(of([]));
    api.flashcards.mockReturnValue(of(cards));
    const fixture = TestBed.configureTestingModule({
      imports: [FlashcardsPage],
      providers: [{ provide: ApiClient, useValue: api }],
    }).createComponent(FlashcardsPage);
    fixture.detectChanges();
    return { fixture, page: fixture.componentInstance as any };
  };

  // Ancorado pelo aria-label: seletor de atributo em `style` não é suportado pelo jsdom.
  const cardButton = (fixture: any) =>
    fixture.nativeElement.querySelector(
      '[aria-label="Mostrar resposta"], [aria-label="Mostrar pergunta"]',
    ) as HTMLElement;
  const face = (fixture: any) => cardButton(fixture).firstElementChild as HTMLElement;
  const pressKey = (key: string, target: EventTarget | null = document) => {
    const event = new KeyboardEvent('keydown', { key, bubbles: true });
    Object.defineProperty(event, 'target', { value: target });
    document.dispatchEvent(event);
  };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.resetTestingModule();
  });

  describe('revisão', () => {
    it('começa virada para a frente', () => {
      const { fixture, page } = build();
      page.study();

      fixture.detectChanges();

      expect(page.flipped()).toBe(false);
      expect(face(fixture).style.transform).toBe('rotateY(0deg)');
      fixture.nativeElement.querySelector('[aria-label="Mostrar resposta"]');
    });

    it('vira o card ao tocar e volta ao tocar de novo', () => {
      const { fixture, page } = build();
      page.study();
      fixture.detectChanges();
      cardButton(fixture).click();
      fixture.detectChanges();
      expect(page.flipped()).toBe(true);
      expect(face(fixture).style.transform).toBe('rotateY(180deg)');

      (
        fixture.nativeElement.querySelector('[aria-label="Mostrar pergunta"]') as HTMLElement
      ).click();
      fixture.detectChanges();
      expect(face(fixture).style.transform).toBe('rotateY(0deg)');
    });

    it('as duas faces ficam escondidas uma da outra', () => {
      // Sem backface-hidden o verso aparece espelhado por baixo da frente durante a virada.
      const { fixture, page } = build();
      page.study();
      fixture.detectChanges();

      const faces = face(fixture).children as unknown as HTMLElement[];

      expect(faces.length).toBe(2);
      for (const el of faces) expect(el.className).toContain('backface-visibility');
    });

    it('mostra o progresso em barra e em número', () => {
      const { fixture, page } = build();
      page.study();
      fixture.detectChanges();

      expect(page.progress()).toBeCloseTo(33.33, 1);
      expect(fixture.nativeElement.textContent).toContain('1 / 3');
    });

    it('espaço vira, setas navegam e esc fecha', () => {
      const { page } = build();
      page.study();

      pressKey(' ');
      expect(page.flipped()).toBe(true);

      pressKey('ArrowRight');
      expect(page.index()).toBe(1);
      // Trocar de card sempre volta para a frente: senão aparece a resposta do card errado.
      expect(page.flipped()).toBe(false);

      pressKey('ArrowLeft');
      expect(page.index()).toBe(0);

      pressKey('Escape');
      expect(page.studyOpen()).toBe(false);
    });

    it('não sequestra a barra de espaço de quem está digitando', () => {
      const { page } = build();
      page.study();
      const campo = document.createElement('input');

      pressKey(' ', campo);

      expect(page.flipped()).toBe(false);
    });

    it('ignora o teclado quando a revisão não está aberta', () => {
      const { page } = build();

      pressKey(' ');

      expect(page.flipped()).toBe(false);
    });

    it('volta ao início e mostra a frente ao abrir', () => {
      const { page } = build();
      page.index.set(2);
      page.flipped.set(true);

      page.study();

      expect(page.index()).toBe(0);
      expect(page.flipped()).toBe(false);
    });
  });

  describe('sem cards', () => {
    it('não abre revisão vazia', () => {
      const { page } = build([]);

      page.study();
      page.next(1);

      expect(page.index()).toBe(0);
    });
  });
});
