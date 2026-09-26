import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiClient } from 'api-client';

@Component({ standalone: true, imports: [RouterLink], templateUrl: './verify.component.html' })
export class VerifyPage {
  private readonly api = inject(ApiClient);
  private readonly route = inject(ActivatedRoute);
  protected readonly message = signal('Confirmando seu e-mail…');
  protected readonly failed = signal(false);
  constructor() {
    this.api.verifyEmail(this.route.snapshot.queryParamMap.get('token') ?? '').subscribe({
      next: () => this.message.set('E-mail confirmado.'),
      error: () => {
        this.failed.set(true);
        this.message.set('O link é inválido ou expirou.');
      },
    });
  }
}
