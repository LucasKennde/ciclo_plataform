import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  ApiClient,
  AiModel,
  AiProvidersOverview,
  IdentitySettings,
  MailConfiguration,
} from 'api-client';
import { CardComponent, PageHeaderComponent } from 'ui';

@Component({
  imports: [ReactiveFormsModule, DatePipe, DecimalPipe, PageHeaderComponent, CardComponent],
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
  modelBusy = signal<string | null>(null);
  pendingForceSave = signal<{ provider: string; key: string } | null>(null);
  private keyDrafts = signal<Record<string, string>>({});
  audit = signal<any[]>([]);
  identitySettings = signal<IdentitySettings | null>(null);
  mailConfiguration = signal<MailConfiguration | null>(null);
  securityMessage = signal('');
  securityError = signal('');

  /**
   * Seleção dos <select> de roteamento fica em signal por operação, e não lida direto do DOM via
   * template reference: ler #provider.value dentro do @for dos modelos mantinha o valor antigo do
   * modelo quando o provedor mudava, e a troca de provider/model.reset() apagava o que o admin
   * tinha acabado de aplicar.
   */
  routeSelection = signal<Record<string, { provider: string; model: string }>>({});
  modelOperations = signal<Set<string>>(new Set());

  modelForm = this.fb.nonNullable.group({
    provider: ['OPENAI', Validators.required],
    id: ['', [Validators.required]],
    label: ['', [Validators.required]],
    inputUsdPerMillion: [0, [Validators.required, Validators.min(0)]],
    outputUsdPerMillion: [0, [Validators.required, Validators.min(0)]],
  });
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
    this.loadProviders();
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
        this.api.aiProviders().subscribe((v) => {
          this.aiBusy.set(null);
          this.applyOverview(v);
          this.aiMessage.set('Chave removida.');
        });
      },
      error: (e: HttpErrorResponse) => {
        this.aiBusy.set(null);
        this.aiError.set(this.describe(e, 'Não foi possível remover a chave.'));
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
          this.applyOverview(v);
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
        this.aiError.set(this.describe(e, 'Não foi possível salvar a credencial.'));
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
        this.aiError.set(this.describe(e, 'Não foi possível testar a credencial.'));
      },
    });
  }
  private clearAiFeedback() {
    this.aiMessage.set('');
    this.aiError.set('');
    this.pendingForceSave.set(null);
  }

  // --- Modelos cadastrados ------------------------------------------------------------------

  operations(): string[] {
    return (this.providers()?.operations ?? []).map((o) => o.id);
  }
  operationLabel(operation: string): string {
    return this.providers()?.operations.find((o) => o.id === operation)?.label ?? operation;
  }
  modelFormOperations(): string[] {
    return this.operations();
  }
  toggleModelOperation(operation: string) {
    this.modelOperations.update((current) => {
      const next = new Set(current);
      if (!next.delete(operation)) next.add(operation);
      return next;
    });
  }
  hasModelOperation(operation: string): boolean {
    return this.modelOperations().has(operation);
  }
  setAllModelOperations(selected: boolean) {
    this.modelOperations.set(selected ? new Set(this.operations()) : new Set());
  }
  saveModel() {
    this.clearAiFeedback();
    if (this.modelForm.invalid) {
      this.modelForm.markAllAsTouched();
      this.aiError.set('Preencha provedor, id, nome e os dois preços.');
      return;
    }
    const operations = [...this.modelOperations()];
    if (!operations.length) {
      this.aiError.set('Marque ao menos uma operação para o modelo.');
      return;
    }
    const form = this.modelForm.getRawValue();
    this.modelBusy.set('save');
    this.api.saveAiModel({ ...form, operations }).subscribe({
      next: (v) => {
        this.modelBusy.set(null);
        this.applyOverview(v);
        this.modelForm.reset({
          provider: form.provider,
          id: '',
          label: '',
          inputUsdPerMillion: 0,
          outputUsdPerMillion: 0,
        });
        this.aiMessage.set(`Modelo ${form.id} cadastrado em ${form.provider}.`);
      },
      error: (e: HttpErrorResponse) => {
        this.modelBusy.set(null);
        this.aiError.set(this.describe(e, 'Não foi possível cadastrar o modelo.'));
      },
    });
  }
  removeModel(provider: string, model: AiModel) {
    if (!confirm(`Remover o modelo ${model.label} (${model.id}) de ${provider}?`)) return;
    this.clearAiFeedback();
    this.modelBusy.set(model.id);
    this.api.removeAiModel(provider, model.id).subscribe({
      next: (v) => {
        this.modelBusy.set(null);
        this.applyOverview(v);
        this.aiMessage.set('Modelo removido.');
      },
      error: (e: HttpErrorResponse) => {
        this.modelBusy.set(null);
        this.aiError.set(this.describe(e, 'Não foi possível remover o modelo.'));
      },
    });
  }
  providerLabel(provider: string): string {
    return this.providers()?.providers.find((p) => p.id === provider)?.label ?? provider;
  }

  // --- Roteamento ---------------------------------------------------------------------------

  selectedProvider(operation: string): string {
    return this.routeSelection()[operation]?.provider ?? '';
  }
  selectedModel(operation: string): string {
    return this.routeSelection()[operation]?.model ?? '';
  }
  selectProvider(operation: string, provider: string) {
    this.routeSelection.update((current) => ({
      ...current,
      // Trocar de provedor invalida o modelo: sem esta limpeza o valor antigo continuava no
      // <select> e podia ser aplicado contra o provedor errado.
      [operation]: { provider, model: '' },
    }));
  }
  selectModel(operation: string, model: string) {
    this.routeSelection.update((current) => ({
      ...current,
      [operation]: { provider: current[operation]?.provider ?? '', model },
    }));
  }
  models(provider: string, operation?: string): AiModel[] {
    const list = this.providers()?.providers?.find((p) => p.id === provider)?.models ?? [];
    return operation ? list.filter((m) => m.operations.includes(operation)) : list;
  }
  hasModelFor(provider: string, operation: string): boolean {
    return this.models(provider, operation).length > 0;
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
        this.applyOverview(v);
        this.aiMessage.set(`Rota de ${this.operationLabel(operation)} aplicada.`);
      },
      error: (e: HttpErrorResponse) =>
        this.aiError.set(this.describe(e, 'Não foi possível atualizar a rota.')),
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
  private loadProviders() {
    this.api.aiProviders().subscribe({
      next: (v) => this.applyOverview(v),
      error: (e: HttpErrorResponse) =>
        this.aiError.set(this.describe(e, 'Falha ao carregar a IA.')),
    });
  }
  private loadSecurity() {
    this.api.identitySettings().subscribe((settings) => {
      this.identitySettings.set(settings);
      this.identityForm.patchValue(settings);
    });
    this.api.mailConfiguration().subscribe((mail) => this.mailConfiguration.set(mail));
  }
  /**
   * O Overview é a única fonte de verdade dos <select>: recarregá-lo realinha a seleção com a rota
   * efetivamente gravada, então o admin nunca vê um modelo selecionado que o backend não aceitou.
   */
  private applyOverview(overview: AiProvidersOverview) {
    this.providers.set(overview);
    const selection: Record<string, { provider: string; model: string }> = {};
    for (const [operation, route] of Object.entries(overview.routing ?? {})) {
      selection[operation] = { provider: route.provider, model: route.model };
    }
    this.routeSelection.set(selection);
    if (!this.modelOperations().size && overview.operations?.length) {
      this.modelOperations.set(new Set(overview.operations.map((o) => o.id)));
    }
  }
  private refreshAudit() {
    this.api.adminAudit().subscribe((audit) => this.audit.set(audit));
  }
  private clearSecurityFeedback() {
    this.securityMessage.set('');
    this.securityError.set('');
  }
  private showSecurityError(error: any) {
    this.securityError.set(this.describe(error, 'Não foi possível concluir a operação.'));
  }
  /** O backend devolve {message, fields} em 422; os campos são o que diz o que falta. */
  private describe(error: any, fallback: string): string {
    const message = error?.error?.message ?? fallback;
    const fields = error?.error?.fields;
    if (!fields || !Object.keys(fields).length) return message;
    const detail = Object.entries(fields)
      .map(([field, text]) => `${field}: ${text}`)
      .join(' · ');
    return `${message} (${detail})`;
  }
}
