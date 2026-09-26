import { Injectable, computed, inject, signal } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { catchError, map, of, tap } from 'rxjs';
import { ApiClient, User } from 'api-client';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);
  readonly user = signal<User | null>(null);
  readonly authenticated = computed(() => this.user() !== null);
  login(email: string, password: string) {
    return this.api.login(email, password).pipe(tap((result) => this.user.set(result.user)));
  }
  load() {
    return this.api.me().pipe(
      tap((user) => this.user.set(user)),
      map(() => true),
      catchError(() => of(false)),
    );
  }
  logout() {
    this.api.logout().subscribe({
      complete: () => {
        this.user.set(null);
        this.router.navigateByUrl('/login');
      },
      error: () => {
        this.user.set(null);
        this.router.navigateByUrl('/login');
      },
    });
  }
}

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.user()
    ? true
    : auth.load().pipe(map((ok) => (ok ? true : router.createUrlTree(['/login']))));
};
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const decide = () => (auth.user()?.role === 'ADMIN' ? true : router.createUrlTree(['/login']));
  return auth.user() ? decide() : auth.load().pipe(map(decide));
};
