import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthResponse, ApiClient } from 'api-client';
import { catchError, finalize, Observable, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { AuthService } from './auth';

/**
 * Rotas que já respondem sobre a própria sessão. Interceptá-las criaria laço: o 401 do refresh
 * dispararia outro refresh, indefinidamente.
 */
const SESSION_ROUTES = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'];

/**
 * Uma renovação por vez, compartilhada entre as requisições pendentes.
 *
 * <p>Sem a trava, a tela inicial dispara várias chamadas em paralelo e cada 401 pediria um refresh.
 * O servidor rotaciona e invalida o token antigo (`consumeAndRotate`), então a segunda chamada
 * chega com token já queimado e morre com "Sessão inválida" — derrubando o usuário que só queria
 * renovar a sessão. É o erro clássico de quem implementa isso sem trava.
 */
let refreshing$: Observable<AuthResponse> | null = null;

function refreshAccessToken(api: ApiClient): Observable<AuthResponse> {
  if (!refreshing$) {
    refreshing$ = api.refresh().pipe(
      // refCount false: o resultado precisa chegar às requisições que já estavam esperando, mesmo
      // depois que a última delas desativa.
      shareReplay({ bufferSize: 1, refCount: false }),
      finalize(() => (refreshing$ = null)),
    );
  }
  return refreshing$;
}

/**
 * Renova o cookie de acesso quando ele expira, e reenvia a requisição.
 *
 * <p>O cookie `ciclo_access` vive 15 minutos e o `ciclo_refresh` vive 30 dias, mas nada chamava a
 * renovação: aos 15 minutos toda chamada começava a dar 401 e o usuário era jogado no login com a
 * sessão ainda válida no servidor. Reativo de propósito — renovar antes de expirar exigiria saber
 * a hora da emissão, que o navegador não expõe para cookie HttpOnly.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (SESSION_ROUTES.some((route) => req.url.includes(route))) return next(req);

  const api = inject(ApiClient);
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((error) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401)
        return throwError(() => error);
      return refreshAccessToken(api).pipe(
        // Só a falha da RENOVAÇÃO encerra a sessão. Um erro na requisição repetida não deve
        // deslogar ninguém: a sessão pode estar sana e o erro ser qualquer outro.
        tap({
          error: () => {
            auth.expire();
            void router.navigateByUrl('/login');
          },
        }),
        // O cookie novo já foi gravado pelo servidor na resposta do refresh.
        switchMap(() => next(req)),
      );
    }),
  );
};
