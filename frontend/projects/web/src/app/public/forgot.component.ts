import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiClient } from 'api-client';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './forgot.component.html',
})
export class ForgotPage {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApiClient);
  protected readonly message = signal('');
  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  protected send(): void {
    this.api
      .forgotPassword(this.form.getRawValue().email)
      .subscribe(() => this.message.set('Se a conta existir, o link foi enviado.'));
  }
}
