import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiClient, AiProvidersOverview, IdentitySettings, MailConfiguration } from 'api-client';
import { CardComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [ReactiveFormsModule, DatePipe, PageHeaderComponent, CardComponent],
  templateUrl: './settings.component.html',
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
  private keyDrafts = signal<Record<string, string>>({});
  private readonly operationLabels: Record<string, string> = {
    SYLLABUS_EXTRACTION: 'Extração de edital',
    MOCK_EXAM_EXTRACTION: 'Extração de simulado',
    QUESTION_CLASSIFICATION: 'Classificação de questões',
    QUESTION_GENERATION: 'Geração de questões',
  };
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
  keyDraft(provider: string): string {
    return this.keyDrafts()[provider] ?? '';
  }
  setKeyDraft(provider: string, value: string) {
    this.keyDrafts.update((drafts) => ({ ...drafts, [provider]: value }));
  }
  saveKey(provider: string, key: string) {
    const trimmed = key.trim();
    if (trimmed) this.submitKey(provider, trimmed, false);
  }
  forceSaveKey() {
    const pending = this.pendingForceSave();
    if (pending) this.submitKey(pending.provider, pending.key, true);
  }
  removeKey(provider: string) {
    const label = this.providers()?.providers.find((p) => p.id === provider)?.label ?? provider;
    if (!confirm(`Remover a chave de ${label}? Isso não pode ser desfeito.`)) return;
    this.clearAiFeedback();
    this.aiBusy.set(provider);
    this.api.removeProviderKey(provider).subscribe({
      next: () => {
        this.aiMessage.set('Chave removida.');
        this.api.aiProviders().subscribe((v) => {
          this.aiBusy.set(null);
          this.providers.set(v);
        });
      },
      error: (e: HttpErrorResponse) => {
        this.aiBusy.set(null);
        this.aiError.set(e.error?.message ?? 'Não foi possível remover a chave.');
      },
    });
  }
  private submitKey(provider: string, key: string, force: boolean) {
    this.clearAiFeedback();
    this.aiBusy.set(provider);
    this.api.saveProviderKey(provider, key, force).subscribe({
      next: () => {
        this.pendingForceSave.set(null);
        this.setKeyDraft(provider, '');
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
  operationLabel(operation: string): string {
    return this.operationLabels[operation] ?? operation;
  }
  routeLabel(operation: string): string {
    const route = this.providers()?.routing?.[operation];
    if (!route) return 'não configurado';
    const provider = this.providers()?.providers.find((p) => p.id === route.provider);
    const model = provider?.models.find((m) => m.id === route.model);
    return `${provider?.label ?? route.provider} · ${model?.label ?? route.model}`;
  }
  route(operation: string, provider: string, model: string) {
    if (!provider || !model) return;
    // No confirm() here: unlike removing a key, a route can just be switched again, and the
    // current route is already visible before applying, with success/error shown right after.
    this.clearAiFeedback();
    this.api.updateRoutes([{ operation, provider, model }]).subscribe({
      next: (v) => {
        this.providers.set(v);
        this.aiMessage.set('Rota atualizada.');
      },
      error: (e: HttpErrorResponse) =>
        this.aiError.set(e.error?.message ?? 'Não foi possível atualizar a rota.'),
    });
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
