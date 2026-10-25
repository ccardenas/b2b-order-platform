import { HttpStatus, INestApplication, Logger, ValidationPipe } from '@nestjs/common';
import { ValidationError } from 'class-validator';
import { accessLog } from './common/access-log.middleware';
import { ApiError, ErrorCode } from './common/api-error';
import { HttpExceptionFilter } from './common/http-exception.filter';
import { traceMiddleware } from './common/trace';
import { FaultInjectionInterceptor } from './faults/fault-injection.interceptor';

export interface AppOptions {
  faultInjectionEnabled: boolean;
  slowDelayMs?: number;
}

// Configuracion compartida por main.ts y los tests e2e, para que los tests
// ejerciten exactamente el mismo pipeline que produccion.
export function configureApp(app: INestApplication, options: AppOptions): void {
  app.use(traceMiddleware);
  app.use(accessLog(new Logger('HTTP')));
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      forbidNonWhitelisted: true,
      exceptionFactory: (errors: ValidationError[]) =>
        new ApiError(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, validationMessage(errors)),
    }),
  );
  app.useGlobalFilters(new HttpExceptionFilter());
  if (options.faultInjectionEnabled) {
    app.useGlobalInterceptors(new FaultInjectionInterceptor(options.slowDelayMs));
  }
}

function validationMessage(errors: ValidationError[]): string {
  return errors.flatMap((error) => Object.values(error.constraints ?? {})).join('; ') || 'Invalid request';
}
