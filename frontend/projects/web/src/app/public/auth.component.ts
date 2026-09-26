import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiClient } from 'api-client';
import { AuthService } from 'auth';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './auth.component.html',
})
export class LoginPage {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApiClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly registering = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly success = signal('');
  readonly form = this.fb.nonNullable.group({
    displayName: [''],
    workspaceName: [''],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(10)]],
  });

  protected toggleMode(): void {
    this.registering.update((value) => !value);
    this.error.set('');
    this.success.set('');
  }

  submit(): void {
    if (this.form.invalid) return;
    this.error.set('');
    this.success.set('');
    this.busy.set(true);
    const value = this.form.getRawValue();
    if (this.registering()) {
      this.api
        .register(value)
        .pipe(finalize(() => this.busy.set(false)))
        .subscribe({
          next: (result) =>
            this.success.set(
              result.emailVerificationRequired
                ? 'Conta criada. Confira o e-mail para confirmar o cadastro.'
                : 'Conta criada. Você já pode entrar.',
            ),
          error: (error) =>
            this.error.set(error.error?.message ?? 'Não foi possível criar a conta.'),
        });
      return;
    }
    this.auth
      .login(value.email, value.password)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (result) =>
          result.user.role === 'ADMIN'
            ? window.location.assign('http://localhost:4201')
            : void this.router.navigateByUrl('/app'),
        error: (error) => this.error.set(error.error?.message ?? 'Credenciais inválidas.'),
      });
  }
}
