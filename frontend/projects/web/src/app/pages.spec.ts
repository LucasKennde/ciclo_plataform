import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiClient, RegistrationResponse } from 'api-client';
import { AuthService } from 'auth';
import { LoginPage } from './pages';

describe('LoginPage', () => {
  const api = {
    register: vi.fn(),
  };
  const auth = {
    login: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: AuthService, useValue: auth },
      ],
    }).compileComponents();
  });

  it.each([
    [false, 'Conta criada. Você já pode entrar.'],
    [true, 'Conta criada. Confira o e-mail para confirmar o cadastro.'],
  ])('shows the message returned by the verification policy', (required, expected) => {
    const response: RegistrationResponse = {
      userId: 'user-id',
      workspaceId: 'workspace-id',
      email: 'student@example.com',
      emailVerificationRequired: required,
    };
    api.register.mockReturnValue(of(response));
    const fixture = TestBed.createComponent(LoginPage);
    const component = fixture.componentInstance;
    component.registering.set(true);
    component.form.setValue({
      displayName: 'Student',
      workspaceName: 'Meu espaço',
      email: 'student@example.com',
      password: 'Password123',
    });

    component.submit();

    expect(component.success()).toBe(expected);
    expect(component.busy()).toBe(false);
  });
});
