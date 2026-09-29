import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AiProviderView, ApiClient, MailConfiguration, User } from 'api-client';
import { SettingsPage, UsersPage } from './pages';

describe('admin identity controls', () => {
  const pendingUser: User = {
    id: 'user-id',
    email: 'student@example.com',
    displayName: 'Student',
    role: 'STUDENT',
    status: 'PENDING_VERIFICATION',
  };
  const mail: MailConfiguration = {
    mode: 'LOCAL_CAPTURE',
    configured: true,
    host: 'mailpit',
    port: 1025,
    usernameMasked: '',
    from: 'no-reply@ciclo.local',
    authentication: false,
    startTls: false,
  };
  const api = {
    users: vi.fn().mockReturnValue(of({ items: [pendingUser], total: 1, page: 0, size: 20 })),
    confirmUserEmail: vi.fn().mockReturnValue(of({ ...pendingUser, status: 'ACTIVE' })),
    resendUserVerification: vi.fn().mockReturnValue(of(undefined)),
    updateUser: vi.fn(),
    revokeSessions: vi.fn(),
    settings: vi.fn().mockReturnValue(
      of({
        productName: 'Ciclo',
        supportEmail: 'contato@ciclo.local',
        description: '',
        locale: 'pt-BR',
        timezone: 'America/Fortaleza',
        maintenanceMode: false,
      }),
    ),
    aiProviders: vi.fn().mockReturnValue(of({ providers: [] })),
    adminAudit: vi.fn().mockReturnValue(of([])),
    identitySettings: vi.fn().mockReturnValue(
      of({
        emailVerificationRequired: false,
        updatedAt: '2026-09-25T00:00:00Z',
        updatedBy: 'system',
      }),
    ),
    mailConfiguration: vi.fn().mockReturnValue(of(mail)),
    updateIdentitySettings: vi.fn().mockReturnValue(
      of({
        emailVerificationRequired: true,
        updatedAt: '2026-09-25T00:00:00Z',
        updatedBy: 'admin',
      }),
    ),
    testMail: vi.fn().mockReturnValue(of(undefined)),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.users.mockReturnValue(of({ items: [pendingUser], total: 1, page: 0, size: 20 }));
    TestBed.configureTestingModule({
      imports: [UsersPage, SettingsPage],
      providers: [{ provide: ApiClient, useValue: api }],
    });
  });

  it('confirms and reloads a pending user', () => {
    const fixture = TestBed.createComponent(UsersPage);
    const component = fixture.componentInstance;

    component.confirmEmail(pendingUser);

    expect(api.confirmUserEmail).toHaveBeenCalledWith('user-id');
    expect(component.message()).toContain('student@example.com');
    expect(api.users).toHaveBeenCalledTimes(2);
  });

  it('updates the verification policy and sends a test message', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    component.identityForm.setValue({ emailVerificationRequired: true });
    component.mailTestForm.setValue({ email: 'owner@example.com' });

    component.saveIdentitySettings();
    component.sendTestMail();

    expect(api.updateIdentitySettings).toHaveBeenCalledWith(true);
    expect(api.testMail).toHaveBeenCalledWith('owner@example.com');
    expect(component.securityMessage()).toBe('E-mail de teste enviado.');
  });
});

describe('admin AI provider controls', () => {
  const provider: AiProviderView = {
    id: 'OPENAI',
    label: 'OpenAI',
    configured: true,
    source: 'PANEL',
    last4: 'abcd',
    updatedAt: '2026-09-27T00:00:00Z',
    lastTestOk: true,
    lastTestError: null,
    models: [],
  };
  const api = {
    settings: vi.fn().mockReturnValue(of({})),
    aiProviders: vi.fn().mockReturnValue(of({ providers: [provider], routing: {} })),
    adminAudit: vi.fn().mockReturnValue(of([])),
    identitySettings: vi.fn().mockReturnValue(of({})),
    mailConfiguration: vi.fn().mockReturnValue(of(null)),
    saveProviderKey: vi.fn(),
    removeProviderKey: vi.fn(),
    testProvider: vi.fn(),
    updateRoutes: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.settings.mockReturnValue(of({}));
    api.aiProviders.mockReturnValue(of({ providers: [provider], routing: {} }));
    TestBed.configureTestingModule({
      imports: [SettingsPage],
      providers: [{ provide: ApiClient, useValue: api }],
    });
  });

  // Previously, saveKey()/test() called .subscribe() with no error handler at all: a failed
  // provider test (bad key, unreachable API) silently vanished with no feedback in the UI.
  it('surfaces the backend error when saving a key fails, offering to save anyway', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    api.saveProviderKey.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 422,
            error: { message: 'O provedor rejeitou a credencial (HTTP 401).' },
          }),
      ),
    );

    component.saveKey('OPENAI', 'sk-invalid-key');

    expect(api.saveProviderKey).toHaveBeenCalledWith('OPENAI', 'sk-invalid-key', false);
    expect(component.aiError()).toBe('O provedor rejeitou a credencial (HTTP 401).');
    expect(component.pendingForceSave()).toEqual({ provider: 'OPENAI', key: 'sk-invalid-key' });
  });

  it('force-saves the pending key and reports whether it was actually validated', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    api.saveProviderKey.mockReturnValue(of({ ...provider, lastTestOk: false }));
    api.aiProviders.mockReturnValue(
      of({ providers: [{ ...provider, lastTestOk: false }], routing: {} }),
    );
    component.pendingForceSave.set({ provider: 'OPENAI', key: 'sk-untested-key' });

    component.forceSaveKey();

    expect(api.saveProviderKey).toHaveBeenCalledWith('OPENAI', 'sk-untested-key', true);
    expect(component.aiMessage()).toBe('Credencial salva sem confirmação de teste.');
    expect(component.pendingForceSave()).toBeNull();
  });

  it('reports a failed live test without touching the saved credential', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    api.testProvider.mockReturnValue(of({ ok: false, error: 'Tempo de conexão esgotado.' }));
    const overviewFetches = api.aiProviders.mock.calls.length; // called once by the constructor

    component.test('OPENAI');

    expect(component.aiError()).toBe('Tempo de conexão esgotado.');
    expect(api.aiProviders.mock.calls.length).toBe(overviewFetches);
  });

  // removeKey() and route() are destructive/consequential enough to gate behind confirm(),
  // matching the pattern already used for deleting a competition in the web app.
  it('does not remove the key when the confirmation is declined', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    component.removeKey('OPENAI');

    expect(api.removeProviderKey).not.toHaveBeenCalled();
  });

  it('removes the key and refreshes the overview once confirmed', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.removeProviderKey.mockReturnValue(of(undefined));
    api.aiProviders.mockReturnValue(of({ providers: [], routing: {} }));

    component.removeKey('OPENAI');

    expect(api.removeProviderKey).toHaveBeenCalledWith('OPENAI');
    expect(component.aiMessage()).toBe('Chave removida.');
    expect(component.providers()?.providers).toEqual([]);
  });

  // Unlike removeKey(), route() does NOT gate on confirm(): the current route is already
  // visible before applying, and it's a frequently-repeated action while tuning routing, not a
  // one-off destructive one, so a blocking dialog on every click would only get in the way.
  it('applies the route immediately and reports success, without a confirm prompt', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;
    const confirmSpy = vi.spyOn(window, 'confirm');
    api.updateRoutes.mockReturnValue(of({ providers: [provider], routing: {} }));

    component.route('SYLLABUS_EXTRACTION', 'OPENAI', 'gpt-5.4-mini');

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(api.updateRoutes).toHaveBeenCalledWith([
      { operation: 'SYLLABUS_EXTRACTION', provider: 'OPENAI', model: 'gpt-5.4-mini' },
    ]);
    expect(component.aiMessage()).toBe('Rota atualizada.');
  });

  it('does nothing when the route or model is left unpicked', () => {
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;

    component.route('SYLLABUS_EXTRACTION', '', 'gpt-5.4-mini');
    component.route('SYLLABUS_EXTRACTION', 'OPENAI', '');

    expect(api.updateRoutes).not.toHaveBeenCalled();
  });

  it('shows the current provider and model for an operation, in plain text', () => {
    // The routing <select>s can't reliably reflect the active route (native <select> plus
    // dynamically rendered <option>s races with Angular's change detection), so the current
    // route is rendered as plain text instead, driven by this pure lookup.
    api.aiProviders.mockReturnValue(
      of({
        providers: [{ ...provider, models: [{ id: 'gpt-5.4-mini', label: 'GPT-5.4 mini' }] }],
        routing: { SYLLABUS_EXTRACTION: { provider: 'OPENAI', model: 'gpt-5.4-mini' } },
      }),
    );
    const fixture = TestBed.createComponent(SettingsPage);
    const component = fixture.componentInstance;

    expect(component.routeLabel('SYLLABUS_EXTRACTION')).toBe('OpenAI · GPT-5.4 mini');
    expect(component.routeLabel('MOCK_EXAM_EXTRACTION')).toBe('não configurado');
  });
});
