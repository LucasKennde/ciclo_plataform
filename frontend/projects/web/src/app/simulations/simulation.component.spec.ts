import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { ApiClient, Question, Simulation } from 'api-client';
import { SimulationPage } from './simulation.component';

const question = (id: string): Question =>
  ({
    id,
    competitionId: 'competition-id',
    topicId: 'topic-1',
    origin: 'AI_GENERATED',
    status: 'PUBLISHED',
    boardStyle: 'Cebraspe',
    difficulty: 3,
    statement: `Enunciado ${id}`,
    alternatives: ['a', 'b', 'c', 'd'],
    correctIndex: 2,
    explanation: 'Porque sim',
    sources: [],
    createdAt: '2026-10-01T00:00:00Z',
  }) as Question;

const simulation = (status: Simulation['status'], answered: number): Simulation =>
  ({
    id: 'simulation-id',
    competitionId: 'competition-id',
    title: 'Simulado',
    status,
    items: [
      { questionId: 'q1', topicId: 'topic-1', selectedIndex: answered ? 0 : null, correctIndex: 2 },
      { questionId: 'q2', topicId: 'topic-1', selectedIndex: answered ? 1 : null, correctIndex: 2 },
    ],
    durationMinutes: 40,
    startedAt: '2026-10-01T00:00:00Z',
    finishedAt: status === 'FINISHED' ? '2026-10-01T01:00:00Z' : null,
  }) as Simulation;

describe('SimulationPage', () => {
  const api = {
    simulation: vi.fn(),
    simulationQuestions: vi.fn(),
    finishSimulation: vi.fn(),
    renameSimulation: vi.fn(),
    flashcardsFromSimulation: vi.fn(),
  };

  const build = (status: Simulation['status'] = 'FINISHED', answered = 2) => {
    api.simulation.mockReturnValue(of(simulation(status, answered)));
    api.simulationQuestions.mockReturnValue(of([question('q1'), question('q2')]));
    TestBed.configureTestingModule({
      imports: [SimulationPage],
      providers: [
        { provide: ApiClient, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'simulation-id' } } },
        },
      ],
    });
    const fixture = TestBed.createComponent(SimulationPage);
    fixture.detectChanges();
    return fixture.componentInstance as any;
  };

  beforeEach(() => vi.clearAllMocks());

  describe('gerar flashcards do simulado', () => {
    it('manda o concurso e o simulado e conta o que entrou', () => {
      api.flashcardsFromSimulation.mockReturnValue(
        of({ simulationId: 'simulation-id', criados: 2, ignorados: 0, erros: 1 }),
      );
      const page = build();

      page.makeFlashcards();

      expect(api.flashcardsFromSimulation).toHaveBeenCalledWith('competition-id', 'simulation-id');
      expect(page.cardsNotice()).toContain('2 flashcards criados');
      // Falhou uma, então o usuário sabe que o material vai além do que acertou.
      expect(page.cardsNotice()).toContain('1 erro');
    });

    it('avisa que nada foi criado quando as questões já viraram cartão', () => {
      api.flashcardsFromSimulation.mockReturnValue(
        of({ simulationId: 'simulation-id', criados: 0, ignorados: 12, erros: 0 }),
      );
      const page = build();

      page.makeFlashcards();

      expect(page.cardsNotice()).toBe('Essas questões já viraram flashcard antes.');
    });

    it('não trava a tela quando o servidor recusa', () => {
      api.flashcardsFromSimulation.mockReturnValue({
        subscribe: (observer: { error: () => void }) => {
          observer.error();
          return { unsubscribe() {} };
        },
      } as never);
      const page = build();

      page.makeFlashcards();

      expect(page.cardsBusy()).toBe(false);
      expect(page.cardsNotice()).toContain('Não foi possível gerar');
    });

    it('ignora clique com o simulado ainda em andamento', () => {
      const page = build('IN_PROGRESS', 0);

      page.makeFlashcards();

      expect(api.flashcardsFromSimulation).not.toHaveBeenCalled();
    });
  });
});
