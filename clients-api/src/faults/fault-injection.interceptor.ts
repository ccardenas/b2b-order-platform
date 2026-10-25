import { CallHandler, ExecutionContext, HttpStatus, NestInterceptor } from '@nestjs/common';
import { Request, Response } from 'express';
import { fromEvent, mergeMap, Observable, of, race, switchMap, throwError, timer } from 'rxjs';
import { ApiError, ErrorCode } from '../common/api-error';
import { ClientClosedRequestError } from '../common/client-closed-request.error';

// Simula fallos de clients-api de forma determinista segun IDs semilla
// (propuesta §10). Solo se registra si FAULT_INJECTION_ENABLED=true y vive
// fuera del dominio: ni el service ni el repositorio saben que existe.
//
// IDs documentados:
//   CLI-FAIL-429  -> 429 RATE_LIMITED con Retry-After: 1
//   CLI-FAIL-500  -> 500 INTERNAL_ERROR (transitorio)
//   CLI-SLOW      -> espera slowDelayMs (mas que el read timeout de 2 s del
//                    cliente) y sigue al handler real (404, no esta en la
//                    semilla). Si el cliente corta antes, la espera termina.
//   CLI-BAD-ENUM  -> 200 con status "SUSPENDED", fuera del enum (S10)
export const FaultId = {
  FAIL_429: 'CLI-FAIL-429',
  FAIL_500: 'CLI-FAIL-500',
  SLOW: 'CLI-SLOW',
  BAD_ENUM: 'CLI-BAD-ENUM',
} as const;

// Supera el read timeout de 2 s de order-processor (§5.3).
export const DEFAULT_SLOW_DELAY_MS = 5_000;

// Interceptor y no middleware: corre despues del ruteo, asi que
// req.params.clientId ya esta resuelto. En /health no hay clientId y no hace nada.
export class FaultInjectionInterceptor implements NestInterceptor {
  constructor(private readonly slowDelayMs: number = DEFAULT_SLOW_DELAY_MS) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const http = context.switchToHttp();
    const clientId = http.getRequest<Request>().params?.clientId;

    switch (clientId) {
      case FaultId.FAIL_429:
        http.getResponse<Response>().setHeader('Retry-After', '1');
        throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, 'Injected fault: rate limited');
      case FaultId.FAIL_500:
        throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, 'Injected fault: internal error');
      case FaultId.BAD_ENUM:
        return of({
          clientId: FaultId.BAD_ENUM,
          name: 'Cliente con enum desconocido',
          status: 'SUSPENDED',
          segment: 'RETAIL',
          taxRegime: 'GENERAL',
          market: 'MX',
        });
      case FaultId.SLOW: {
        // Si el cliente corta antes del delay, race cancela el timer y el
        // handler real no se ejecuta.
        const clientGone = fromEvent(http.getResponse<Response>(), 'close').pipe(
          mergeMap(() => throwError(() => new ClientClosedRequestError())),
        );
        return race(timer(this.slowDelayMs), clientGone).pipe(switchMap(() => next.handle()));
      }
      default:
        return next.handle();
    }
  }
}
