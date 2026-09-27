import { Component, inject, signal } from '@angular/core';
import { ApiClient } from 'api-client';
import { CardComponent, MetricComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [PageHeaderComponent, MetricComponent, CardComponent],
  templateUrl: './dashboard.component.html',
})
export class AdminDashboardPage {
  private api = inject(ApiClient);
  data = signal<any>(null);
  constructor() {
    this.api.adminDashboard().subscribe((v) => this.data.set(v));
  }
}
