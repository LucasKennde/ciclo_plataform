import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { ApiClient } from 'api-client';
import { AuthService } from 'auth';
import { FocusDockComponent } from '../shared/focus-dock.component';
import { icons } from '../shared/icons';

@Component({
  standalone: true,
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, LucideAngularModule, FocusDockComponent],
  templateUrl: './app-shell.component.html',
})
export class AppShell {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);
  protected readonly icons = icons;
  protected readonly moreOpen = signal(false);
  protected readonly initials = computed(() =>
    (this.auth.user()?.displayName || this.auth.user()?.email || 'C').slice(0, 2).toUpperCase(),
  );
  protected readonly nav = [
    { route: '/app', label: 'Hoje', icon: icons.Home, exact: true },
    { route: '/app/plano', label: 'Meu plano', icon: icons.CalendarDays },
    { route: '/app/simulados', label: 'Simulados', icon: icons.FileText },
    { route: '/app/flashcards', label: 'Flashcards', icon: icons.Layers },
    { route: '/app/erros', label: 'Caderno de erros', icon: icons.RotateCcw },
    { route: '/app/progresso', label: 'Progresso', icon: icons.TrendingUp },
  ];

  constructor() {
    if (this.auth.user()?.role !== 'STUDENT') return;
    this.api.onboarding().subscribe({
      next: (state) => {
        const isOnboarding = this.router.url.startsWith('/app/primeiros-passos');
        if (!isOnboarding && !['DISMISSED', 'COMPLETED'].includes(state.status)) {
          this.router.navigateByUrl('/app/primeiros-passos');
        }
      },
      error: () => undefined,
    });
  }
}
