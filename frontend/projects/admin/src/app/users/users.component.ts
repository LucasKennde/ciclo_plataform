import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ApiClient, User } from 'api-client';
import { CardComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [ReactiveFormsModule, PageHeaderComponent, CardComponent],
  templateUrl: './users.component.html',
})
export class UsersPage {
  private api = inject(ApiClient);
  private fb = inject(FormBuilder);
  users = signal<User[]>([]);
  total = signal(0);
  message = signal('');
  error = signal('');
  filter = this.fb.nonNullable.group({ search: [''], status: [''] });
  constructor() {
    this.load();
  }
  load() {
    const v = this.filter.getRawValue();
    this.api.users(v.search, v.status).subscribe((p) => {
      this.users.set(p.items);
      this.total.set(p.total);
    });
  }
  toggle(u: User) {
    this.clearFeedback();
    this.api
      .updateUser(u.id, { status: u.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED' })
      .subscribe({
        next: () => {
          this.message.set(u.status === 'SUSPENDED' ? 'Usuário reativado.' : 'Usuário suspenso.');
          this.load();
        },
        error: (e) => this.showError(e),
      });
  }
  revoke(u: User) {
    this.clearFeedback();
    this.api.revokeSessions(u.id).subscribe({
      next: () => this.message.set('Sessões revogadas.'),
      error: (e) => this.showError(e),
    });
  }
  confirmEmail(u: User) {
    this.clearFeedback();
    this.api.confirmUserEmail(u.id).subscribe({
      next: () => {
        this.message.set(`E-mail de ${u.email} confirmado.`);
        this.load();
      },
      error: (e) => this.showError(e),
    });
  }
  resendVerification(u: User) {
    this.clearFeedback();
    this.api.resendUserVerification(u.id).subscribe({
      next: () => this.message.set(`Verificação reenviada para ${u.email}.`),
      error: (e) => this.showError(e),
    });
  }
  private clearFeedback() {
    this.message.set('');
    this.error.set('');
  }
  private showError(error: any) {
    this.error.set(error.error?.message ?? 'Não foi possível concluir a operação.');
  }
}
