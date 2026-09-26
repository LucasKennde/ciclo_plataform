import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiClient } from 'api-client';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './reset.component.html',
})
export class ResetPage {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApiClient);
  private readonly route = inject(ActivatedRoute);
  protected readonly message = signal('');
  protected readonly form = this.fb.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(10)]],
  });
  protected send(): void {
    this.api
      .resetPassword(
        this.route.snapshot.queryParamMap.get('token') ?? '',
        this.form.getRawValue().password,
      )
      .subscribe({
        next: () => this.message.set('Senha alterada.'),
        error: () => this.message.set('Não foi possível alterar a senha.'),
      });
  }
}
