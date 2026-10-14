import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';
import { ApiClient, AuthResponse } from 'api-client';
import { AuthService } from './auth';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let api: ApiClient;
  let auth: AuthService;
  let http: HttpTestingController;
  let refresh: ReturnType<typeof vi.spyOn>;

  const SESSAO = {
    accessToken: 'a',
    refreshToken: 'r',
    user: { id: 'u1', email: 'a@b.c', role: 'STUDENT' },
    workspaceId: 'w1',
  } as unknown as AuthResponse;

  const semSessao = () =>
    throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }));

  // O ApiClient real é usado: o interceptor roda na cadeia HTTP de verdade, e só a renovação é
  // espiada, porque é a única chamada que não queremos deixar passar pelo teste de integração.
  const build = (renovar: Observable<AuthResponse> = of({} as AuthResponse)) => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ApiClient);
    auth = TestBed.inject(AuthService);
    refresh = vi.spyOn(api, 'refresh').mockReturnValue(renovar);
    return refresh;
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    vi.clearAllMocks();
  });

  it('renova o cookie e repete a requisição que deu 401', () => {
    build();
    let received: unknown = null;
    api.competitions().subscribe((v) => (received = v));

    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/competitions').flush([{ id: 'c1' }]);

    expect(refresh).toHaveBeenCalledTimes(1);
    expect(received).toEqual([{ id: 'c1' }]);
  });

  it('renova uma vez só quando várias requisições caem em 401 juntas', () => {
    // A tela inicial dispara tudo em paralelo. Sem trava, cada 401 pediria um refresh — e como o
    // servidor rotaciona o token, a segunda chamada morreria e derrubaria o usuário.
    // A renovação precisa ficar em voo de verdade: um observable síncrono liberaria a trava antes
    // do segundo 401 e o teste passaria sem provar nada.
    const renovando = new Subject<AuthResponse>();
    build(renovando.asObservable());
    api.competitions().subscribe();
    api.progress().subscribe();
    api.simulations().subscribe();

    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/progress').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/simulations').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(refresh).toHaveBeenCalledTimes(1);

    // Só agora a renovação responde, e as três requisições são repetidas.
    renovando.next(SESSAO);
    renovando.complete();
    http.expectOne('/api/v1/competitions').flush([]);
    http.expectOne('/api/v1/progress').flush({});
    http.expectOne('/api/v1/simulations').flush([]);
  });

  it('libera a trava depois de renovar, para a próxima expiração', () => {
    build();
    api.competitions().subscribe();
    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/competitions').flush([]);

    // Segunda expiração, dentro da mesma sessão: a trava não pode ter ficado presa.
    api.competitions().subscribe();
    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/competitions').flush([]);

    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('encerra a sessão quando a renovação é recusada', () => {
    build(semSessao());
    auth.user.set({ id: 'u1', email: 'a@b.c', role: 'STUDENT' } as never);
    api.competitions().subscribe({ error: () => undefined });

    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(auth.user()).toBeNull();
  });

  it('não renova nem desloga por erro que não seja 401', () => {
    build();
    auth.user.set({ id: 'u1', email: 'a@b.c', role: 'STUDENT' } as never);
    api.competitions().subscribe({ error: () => undefined });

    http.expectOne('/api/v1/competitions').flush('', { status: 500, statusText: 'Server Error' });

    expect(refresh).not.toHaveBeenCalled();
    expect(auth.user()).not.toBeNull();
  });

  it('não desloga por erro na requisição repetida depois de renovar', () => {
    // Sessão renovada com sucesso e a repetida dá 500: deslogar aqui seria errado.
    build();
    auth.user.set({ id: 'u1', email: 'a@b.c', role: 'STUDENT' } as never);
    api.competitions().subscribe({ error: () => undefined });

    http.expectOne('/api/v1/competitions').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/competitions').flush('', { status: 500, statusText: 'Server Error' });

    expect(auth.user()).not.toBeNull();
  });

  it('não renova ao próprio endpoint de sessão, senão vira laço', () => {
    build(semSessao());
    api.login('a@b.c', 'senha').subscribe({ error: () => undefined });

    http.expectOne('/api/v1/auth/login').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(refresh).not.toHaveBeenCalled();
  });
});
