import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from 'auth';

@Component({
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
})
export class AdminLoginPage {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  error = signal('');
  form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  submit() {
    const v = this.form.getRawValue();
    this.auth.login(v.email, v.password).subscribe({
      next: (r) =>
        r.user.role === 'ADMIN'
          ? this.router.navigateByUrl('/dashboard')
          : this.error.set('Esta conta não possui acesso administrativo.'),
      error: (e) => this.error.set(e.error?.message ?? 'Não foi possível entrar.'),
    });
  }
}
