import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiClient, MailConfiguration, User } from 'api-client';
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
