import { PlannedSession, Simulation, StudyPlan } from 'api-client';

export function firstName(email: string): string {
  return email.split('@')[0]?.split(/[._-]/)[0] || 'Estudante';
}

export function daysUntil(value: string | null): number | null {
  if (!value) return null;
  const target = new Date(`${value}T12:00:00`).getTime();
  return Math.max(0, Math.ceil((target - Date.now()) / 86_400_000));
}

export function accuracy(simulation: Simulation): number {
  const answered = simulation.items.filter((item) => item.selectedIndex !== null);
  if (!answered.length) return 0;
  return (
    answered.filter((item) => item.selectedIndex === item.correctIndex).length / answered.length
  );
}

export function todaySessions(plan: StudyPlan | null): PlannedSession[] {
  const today = new Date().toISOString().slice(0, 10);
  return plan?.sessions.filter((session) => session.date === today) ?? [];
}
