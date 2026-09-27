import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  ApiClient,
  AiDashboard,
  AiPolicy,
  AiProvidersOverview,
  IdentitySettings,
  MailConfiguration,
  User,
} from 'api-client';
import { AuthService } from 'auth';
import { CardComponent, MetricComponent, PageHeaderComponent } from 'ui';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `<main class="login">
    <form [formGroup]="form" (ngSubmit)="submit()">
      <div class="brand">
        <b>C</b><span>Ciclo<small>Painel administrativo</small></span>
      </div>
      <h1>Entrar</h1>
      <label>E-mail<input type="email" formControlName="email" /></label
      ><label>Senha<input type="password" formControlName="password" /></label>
      <p class="error">{{ error() }}</p>
      <button class="button">Acessar painel</button>
    </form>
  </main>`,
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

@Component({
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `<div class="shell">
    <aside>
      <div class="brand">
        <b>C</b><span>Ciclo<small>Painel administrativo</small></span>
      </div>
      <nav>
        <a routerLink="/dashboard" routerLinkActive="active">Visão geral</a
        ><a routerLink="/usuarios" routerLinkActive="active">Usuários</a
        ><a routerLink="/consumo-ia" routerLinkActive="active">Consumo de IA</a
        ><a routerLink="/configuracoes" routerLinkActive="active">Configurações</a>
      </nav>
      <button class="link" (click)="auth.logout()">Sair</button>
    </aside>
    <main><router-outlet /></main>
  </div>`,
})
export class AdminShell {
  auth = inject(AuthService);
}

@Component({
  standalone: true,
  imports: [CommonModule, PageHeaderComponent, MetricComponent, CardComponent],
  template: `<ciclo-page-header
      eyebrow="ADMIN"
      title="Visão geral."
      subtitle="Saúde do produto nas últimas 24 horas."
    />
    <div class="metrics">
      <ciclo-metric label="Usuários" [value]="data()?.users ?? 0" /><ciclo-metric
        label="Requisições IA"
        [value]="data()?.ai?.requests ?? 0"
      /><ciclo-metric label="Tokens" [value]="data()?.ai?.totalTokens ?? 0" /><ciclo-metric
        label="Custo estimado"
        [value]="'$ ' + (data()?.ai?.estimatedCostUsd ?? 0).toFixed(4)"
      />
    </div>
    <ciclo-card
      ><h2>Operação</h2>
      <p>
        Falhas de IA: <b>{{ data()?.ai?.errors ?? 0 }}</b> · Bloqueios:
        <b>{{ data()?.ai?.blocked ?? 0 }}</b>
      </p></ciclo-card
    >`,
})
export class AdminDashboardPage {
  private api = inject(ApiClient);
  data = signal<any>(null);
  constructor() {
    this.api.adminDashboard().subscribe((v) => this.data.set(v));
  }
}

@Component({
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, PageHeaderComponent, CardComponent],
  template: `<ciclo-page-header
      eyebrow="CONTAS"
      title="Usuários."
      subtitle="Consulte e gerencie o acesso ao Ciclo."
    />
    <form class="filters" [formGroup]="filter" (ngSubmit)="load()">
      <input placeholder="Nome ou e-mail" formControlName="search" /><select
        formControlName="status"
      >
        <option value="">Todos</option>
        <option>ACTIVE</option>
        <option>SUSPENDED</option>
        <option>PENDING_VERIFICATION</option></select
      ><button class="button">Buscar</button>
    </form>
    <p class="success" *ngIf="message()">{{ message() }}</p>
    <p class="error" *ngIf="error()">{{ error() }}</p>
    <ciclo-card
      ><div class="table">
        <div class="row header">
          <span>Usuário</span><span>Status</span><span>Papel</span><span>Ações</span>
        </div>
        <div class="row" *ngFor="let user of users()">
          <span
            ><b>{{ user.displayName }}</b
            ><small>{{ user.email }}</small></span
          ><span class="pill">{{ user.status }}</span
          ><span>{{ user.role }}</span
          ><span class="actions"
            ><ng-container *ngIf="user.status === 'PENDING_VERIFICATION'; else accessActions">
              <button class="link" (click)="confirmEmail(user)">Confirmar e-mail</button>
              <button class="link" (click)="resendVerification(user)">Reenviar verificação</button>
            </ng-container>
            <ng-template #accessActions>
              <button class="link" (click)="toggle(user)">
                {{ user.status === 'SUSPENDED' ? 'Reativar' : 'Suspender' }}
              </button>
            </ng-template>
            <button class="link" (click)="revoke(user)">Revogar sessões</button></span
          >
        </div>
      </div>
      <p>{{ total() }} usuários</p></ciclo-card
    >`,
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

@Component({
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    PageHeaderComponent,
    MetricComponent,
    CardComponent,
  ],
  template: `<ciclo-page-header
      eyebrow="GOVERNANÇA"
      title="Consumo de IA."
      subtitle="Custos, limites e eventos em um só lugar."
      ><select [value]="hours()" (change)="hours.set(+$any($event.target).value); load()">
        <option value="24">24 horas</option>
        <option value="168">7 dias</option>
        <option value="720">30 dias</option>
      </select></ciclo-page-header
    >
    <div class="metrics">
      <ciclo-metric label="Requisições" [value]="data()?.summary?.requests ?? 0" /><ciclo-metric
        label="Tokens"
        [value]="data()?.summary?.totalTokens ?? 0"
      /><ciclo-metric
        label="Custo"
        [value]="'$ ' + (data()?.summary?.estimatedCostUsd ?? 0).toFixed(4)"
      /><ciclo-metric label="Bloqueadas" [value]="data()?.summary?.blocked ?? 0" />
    </div>
    <ciclo-card
      ><div class="switch-row">
        <div>
          <h3>Kill switch</h3>
          <p>Interrompe imediatamente novas execuções.</p>
        </div>
        <button
          class="button"
          [class.danger-button]="data()?.settings?.killSwitch"
          (click)="toggleKill()"
        >
          {{ data()?.settings?.killSwitch ? 'Reativar IA' : 'Pausar IA' }}
        </button>
      </div>
      <form class="limits" *ngIf="data() as d">
        <label
          >Por minuto<input type="number" [(ngModel)]="d.settings.perMinute" name="minute" /></label
        ><label>Por hora<input type="number" [(ngModel)]="d.settings.perHour" name="hour" /></label
        ><label>Por dia<input type="number" [(ngModel)]="d.settings.perDay" name="day" /></label
        ><button class="button" type="button" (click)="saveLimits()">Salvar limites</button>
      </form></ciclo-card
    >
    <h2>Principais consumidores</h2>
    <ciclo-card
      ><div class="table">
        <div class="row header">
          <span>Principal</span><span>Requisições</span><span>Tokens</span><span>Custo</span>
        </div>
        <div class="row" *ngFor="let c of data()?.consumers">
          <span>{{ c.principal }}</span
          ><span>{{ c.requests }}</span
          ><span>{{ c.totalTokens }}</span
          ><span>{{ c.costUsd | currency: 'USD' }}</span>
        </div>
      </div></ciclo-card
    >
    <h2>Eventos recentes</h2>
    <ciclo-card
      ><div class="table">
        <div class="row header">
          <span>Operação</span><span>Provedor</span><span>Status</span><span>Tokens</span>
        </div>
        <div class="row" *ngFor="let e of data()?.events">
          <span>{{ e.operation }}</span
          ><span>{{ e.provider }} / {{ e.model }}</span
          ><span>{{ e.outcome }}</span
          ><span>{{ e.inputTokens + e.outputTokens }}</span>
        </div>
      </div></ciclo-card
    >`,
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

@Component({
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, PageHeaderComponent, CardComponent],
  template: `<ciclo-page-header
      eyebrow="PLATAFORMA"
      title="Configurações."
      subtitle="Produto, integrações e segurança."
    />
    <div class="tabs">
      <button *ngFor="let t of tabs" (click)="tab.set(t)" [class.active]="tab() === t">
        {{ t }}
      </button>
    </div>
    <ciclo-card *ngIf="tab() === 'Geral'"
      ><form [formGroup]="general" (ngSubmit)="saveGeneral()">
        <label>Nome do produto<input formControlName="productName" /></label
        ><label>E-mail de suporte<input formControlName="supportEmail" /></label
        ><label>Descrição<textarea formControlName="description"></textarea></label>
        <div class="columns">
          <label>Idioma<input formControlName="locale" /></label
          ><label>Fuso horário<input formControlName="timezone" /></label>
        </div>
        <label class="check"
          ><input type="checkbox" formControlName="maintenanceMode" /> Modo manutenção</label
        ><button class="button">Salvar alterações</button>
      </form></ciclo-card
    >
    <section *ngIf="tab() === 'IA'">
      <p class="success" *ngIf="aiMessage()">{{ aiMessage() }}</p>
      <p class="error" *ngIf="aiError()">
        {{ aiError() }}
        <button *ngIf="pendingForceSave()" class="link" (click)="forceSaveKey()">
          Salvar mesmo assim
        </button>
      </p>
      <div class="providers">
        <ciclo-card *ngFor="let p of providers()?.providers"
          ><span class="pill">{{ p.configured ? 'Configurado' : 'Sem chave' }}</span>
          <h2>{{ p.label }}</h2>
          <p *ngIf="p.last4">Chave ••••{{ p.last4 }} · {{ p.source }}</p>
          <p *ngIf="p.lastTestOk === false" class="error">
            Último teste falhou: {{ p.lastTestError }}
          </p>
          <div class="key">
            <input #key placeholder="Cole uma nova API key" [disabled]="aiBusy() === p.id" /><button
              class="button"
              [disabled]="aiBusy() === p.id"
              (click)="saveKey(p.id, key.value)"
            >
              {{ aiBusy() === p.id ? 'Testando…' : 'Salvar e testar' }}</button
            ><button class="link" [disabled]="aiBusy() === p.id" (click)="test(p.id)">
              Testar
            </button>
          </div></ciclo-card
        >
      </div>
      <ciclo-card
        ><h2>Roteamento por operação</h2>
        <div class="route" *ngFor="let op of operations">
          <b>{{ op }}</b
          ><select #provider>
            <option *ngFor="let p of providers()?.providers" [value]="p.id">
              {{ p.label }}
            </option></select
          ><select #model>
            <option *ngFor="let m of models(provider.value)" [value]="m.id">
              {{ m.label }}
            </option></select
          ><button class="button" (click)="route(op, provider.value, model.value)">Aplicar</button>
        </div></ciclo-card
      >
    </section>
    <section *ngIf="tab() === 'Segurança'" class="security-grid">
      <ciclo-card>
        <div class="switch-row">
          <div>
            <h2>Confirmação de e-mail</h2>
            <p>
              Quando desativada, novos usuários entram ativos. Contas que já estão pendentes não são
              alteradas automaticamente.
            </p>
          </div>
          <form [formGroup]="identityForm" (ngSubmit)="saveIdentitySettings()">
            <label class="check">
              <input type="checkbox" formControlName="emailVerificationRequired" />
              Exigir confirmação
            </label>
            <button class="button">Salvar</button>
          </form>
        </div>
        <small *ngIf="identitySettings() as current">
          Última alteração por {{ current.updatedBy }} em {{ current.updatedAt | date: 'short' }}
        </small>
      </ciclo-card>

      <ciclo-card *ngIf="mailConfiguration() as mail">
        <div class="switch-row">
          <div>
            <span class="pill">{{ mail.mode === 'LOCAL_CAPTURE' ? 'Mailpit local' : 'SMTP' }}</span>
            <h2>Entrega de e-mail</h2>
            <p>
              {{ mail.configured ? 'Configuração disponível.' : 'Configuração incompleta.' }}
              As credenciais permanecem protegidas no ambiente.
            </p>
          </div>
        </div>
        <dl class="mail-details">
          <div>
            <dt>Servidor</dt>
            <dd>{{ mail.host }}:{{ mail.port }}</dd>
          </div>
          <div>
            <dt>Remetente</dt>
            <dd>{{ mail.from }}</dd>
          </div>
          <div>
            <dt>Usuário</dt>
            <dd>{{ mail.usernameMasked || 'Não informado' }}</dd>
          </div>
          <div>
            <dt>Segurança</dt>
            <dd>
              Auth {{ mail.authentication ? 'ativa' : 'inativa' }} · TLS
              {{ mail.startTls ? 'ativo' : 'inativo' }}
            </dd>
          </div>
        </dl>
        <form class="inline" [formGroup]="mailTestForm" (ngSubmit)="sendTestMail()">
          <input type="email" formControlName="email" placeholder="Destinatário do teste" />
          <button class="button" [disabled]="mailTestForm.invalid">Enviar teste</button>
        </form>
      </ciclo-card>

      <p class="success" *ngIf="securityMessage()">{{ securityMessage() }}</p>
      <p class="error" *ngIf="securityError()">{{ securityError() }}</p>

      <ciclo-card>
        <h2>Auditoria administrativa</h2>
        <div class="table">
          <div class="row header">
            <span>Ação</span><span>Objeto</span><span>Ator</span><span>Data</span>
          </div>
          <div class="row" *ngFor="let a of audit()">
            <span>{{ a.action }}</span>
            <span>{{ a.subject }}</span>
            <span>{{ a.actor }}</span>
            <span>{{ a.createdAt | date: 'short' }}</span>
          </div>
        </div>
      </ciclo-card>
    </section>`,
})
export class SettingsPage {
  private api = inject(ApiClient);
  private fb = inject(FormBuilder);
  tabs = ['Geral', 'IA', 'Segurança'];
  tab = signal('Geral');
  providers = signal<AiProvidersOverview | null>(null);
  aiMessage = signal('');
  aiError = signal('');
  aiBusy = signal<string | null>(null);
  pendingForceSave = signal<{ provider: string; key: string } | null>(null);
  audit = signal<any[]>([]);
  identitySettings = signal<IdentitySettings | null>(null);
  mailConfiguration = signal<MailConfiguration | null>(null);
  securityMessage = signal('');
  securityError = signal('');
  operations = [
    'SYLLABUS_EXTRACTION',
    'MOCK_EXAM_EXTRACTION',
    'QUESTION_CLASSIFICATION',
    'QUESTION_GENERATION',
  ];
  general = this.fb.nonNullable.group({
    productName: [''],
    supportEmail: [''],
    description: [''],
    locale: ['pt-BR'],
    timezone: ['America/Fortaleza'],
    maintenanceMode: [false],
  });
  identityForm = this.fb.nonNullable.group({
    emailVerificationRequired: [false],
  });
  mailTestForm = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  constructor() {
    this.api.settings().subscribe((v) => this.general.patchValue(v));
    this.api.aiProviders().subscribe((v) => this.providers.set(v));
    this.api.adminAudit().subscribe((v) => this.audit.set(v));
    this.loadSecurity();
  }
  saveGeneral() {
    this.api
      .updateSettings({ ...this.general.getRawValue(), updatedAt: null, updatedBy: null })
      .subscribe((v) => this.general.patchValue(v));
  }
  saveKey(provider: string, key: string) {
    const trimmed = key.trim();
    if (trimmed) this.submitKey(provider, trimmed, false);
  }
  forceSaveKey() {
    const pending = this.pendingForceSave();
    if (pending) this.submitKey(pending.provider, pending.key, true);
  }
  private submitKey(provider: string, key: string, force: boolean) {
    this.clearAiFeedback();
    this.aiBusy.set(provider);
    this.api.saveProviderKey(provider, key, force).subscribe({
      next: () => {
        this.pendingForceSave.set(null);
        this.api.aiProviders().subscribe((v) => {
          this.aiBusy.set(null);
          this.providers.set(v);
          const saved = v.providers.find((p) => p.id === provider);
          this.aiMessage.set(
            saved?.lastTestOk
              ? 'Credencial validada e salva.'
              : 'Credencial salva sem confirmação de teste.',
          );
        });
      },
      error: (e: HttpErrorResponse) => {
        this.aiBusy.set(null);
        // Offer to bypass the live provider test only for a first, untried attempt: a forced
        // save that fails again means the error is unrelated to the test (e.g. bad key format),
        // and retrying force would just repeat it.
        this.pendingForceSave.set(force ? null : { provider, key });
        this.aiError.set(e.error?.message ?? 'Não foi possível salvar a credencial.');
      },
    });
  }
  test(provider: string) {
    this.clearAiFeedback();
    this.aiBusy.set(provider);
    this.api.testProvider(provider).subscribe({
      next: (result) => {
        this.aiBusy.set(null);
        if (result.ok) this.aiMessage.set('Credencial válida.');
        else this.aiError.set(result.error ?? 'O provedor rejeitou a credencial.');
      },
      error: (e: HttpErrorResponse) => {
        this.aiBusy.set(null);
        this.aiError.set(e.error?.message ?? 'Não foi possível testar a credencial.');
      },
    });
  }
  private clearAiFeedback() {
    this.aiMessage.set('');
    this.aiError.set('');
    this.pendingForceSave.set(null);
  }
  models(provider: string) {
    return this.providers()?.providers?.find((p) => p.id === provider)?.models ?? [];
  }
  route(operation: string, provider: string, model: string) {
    if (provider && model)
      this.api
        .updateRoutes([{ operation, provider, model }])
        .subscribe((v) => this.providers.set(v));
  }
  saveIdentitySettings() {
    this.clearSecurityFeedback();
    const required = this.identityForm.getRawValue().emailVerificationRequired;
    this.api.updateIdentitySettings(required).subscribe({
      next: (settings) => {
        this.identitySettings.set(settings);
        this.identityForm.patchValue(settings);
        this.securityMessage.set('Política de confirmação atualizada.');
        this.refreshAudit();
      },
      error: (e) => this.showSecurityError(e),
    });
  }
  sendTestMail() {
    this.clearSecurityFeedback();
    this.api.testMail(this.mailTestForm.getRawValue().email).subscribe({
      next: () => this.securityMessage.set('E-mail de teste enviado.'),
      error: (e) => this.showSecurityError(e),
    });
  }
  private loadSecurity() {
    this.api.identitySettings().subscribe((settings) => {
      this.identitySettings.set(settings);
      this.identityForm.patchValue(settings);
    });
    this.api.mailConfiguration().subscribe((mail) => this.mailConfiguration.set(mail));
  }
  private refreshAudit() {
    this.api.adminAudit().subscribe((audit) => this.audit.set(audit));
  }
  private clearSecurityFeedback() {
    this.securityMessage.set('');
    this.securityError.set('');
  }
  private showSecurityError(error: any) {
    this.securityError.set(error.error?.message ?? 'Não foi possível concluir a operação.');
  }
}
