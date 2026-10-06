import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiAuditEntry, AiDashboard, ApiClient } from 'api-client';
import { CardComponent, MetricComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [
    FormsModule,
    CurrencyPipe,
    DatePipe,
    PageHeaderComponent,
    MetricComponent,
    CardComponent,
  ],
  templateUrl: './ai-usage.component.html',
})
export class AiUsagePage {
  private api = inject(ApiClient);
  data = signal<AiDashboard | null>(null);
  hours = signal(24);
  audit = signal<AiAuditEntry[]>([]);
  constructor() {
    this.load();
  }
  load() {
    this.api.aiUsage(this.hours()).subscribe((v) => this.data.set(v));
    this.api.aiAudit().subscribe((v) => this.audit.set(v));
  }
  /** action vem do backend em SCREAMING_SNAKE; a tela fala português. */
  auditLabel(action: string): string {
    return (
      {
        KEY_SET: 'Credencial salva',
        KEY_REMOVED: 'Credencial removida',
        MODEL_SAVED: 'Modelo cadastrado',
        MODEL_REMOVED: 'Modelo removido',
        ROUTE_CHANGED: 'Rota alterada',
        POLICY_CHANGED: 'Limites alterados',
        PRINCIPAL_BLOCKED: 'Usuário bloqueado',
        PRINCIPAL_UNBLOCKED: 'Bloqueio removido',
      }[action] ?? action
    );
  }

  saveLimits() {
    const d = this.data();
    if (d) this.api.updateAiPolicy(d.settings).subscribe(() => this.load());
  }
  toggleKill() {
    const d = this.data();
    if (d)
      this.api
        .updateAiPolicy({ ...d.settings, killSwitch: !d.settings.killSwitch })
        .subscribe(() => this.load());
  }
}
