import { CurrencyPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiDashboard, ApiClient } from 'api-client';
import { CardComponent, MetricComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [FormsModule, CurrencyPipe, PageHeaderComponent, MetricComponent, CardComponent],
  templateUrl: './ai-usage.component.html',
})
export class AiUsagePage {
  private api = inject(ApiClient);
  data = signal<AiDashboard | null>(null);
  hours = signal(24);
  constructor() {
    this.load();
  }
  load() {
    this.api.aiUsage(this.hours()).subscribe((v) => this.data.set(v));
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
