import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus, Logger, NotFoundException } from '@nestjs/common';
import { Request, Response } from 'express';
import { ClientNotFoundError } from '../clients/client.errors';
import { ApiError, ErrorCode } from './api-error';
import { ClientClosedRequestError } from './client-closed-request.error';
import { getTraceId } from './trace';

interface ErrorBody {
  error: { code: ErrorCode; message: string; traceId: string };
}

// Toda respuesta de error respeta {"error":{"code","message","traceId"}}
// (contracts/http/error.schema.json), incluidas las rutas inexistentes y los
// errores no previstos.
@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(HttpExceptionFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    if (exception instanceof ClientClosedRequestError) {
      return;
    }
    const http = host.switchToHttp();
    const req = http.getRequest<Request>();
    const res = http.getResponse<Response>();
    const traceId = getTraceId(res);

    const { status, code, message } = this.toError(exception, req, traceId);
    const body: ErrorBody = { error: { code, message, traceId } };
    res.status(status).json(body);
  }

  private toError(exception: unknown, req: Request, traceId: string): { status: number; code: ErrorCode; message: string } {
    if (exception instanceof ApiError) {
      return { status: exception.getStatus(), code: exception.code, message: exception.message };
    }
    if (exception instanceof ClientNotFoundError) {
      return { status: HttpStatus.NOT_FOUND, code: ErrorCode.CLIENT_NOT_FOUND, message: exception.message };
    }
    // Nest lanza NotFoundException cuando ninguna ruta coincide, tambien con
    // un metodo equivocado (express no distingue 404 de 405).
    if (exception instanceof NotFoundException) {
      return { status: HttpStatus.NOT_FOUND, code: ErrorCode.ROUTE_NOT_FOUND, message: `No route for ${req.method} ${req.path}` };
    }
    // Otros 4xx del framework (por ejemplo, un body ilegible): error del llamador.
    if (exception instanceof HttpException && exception.getStatus() < 500) {
      return { status: exception.getStatus(), code: ErrorCode.INVALID_REQUEST, message: exception.message };
    }
    // No se expone el detalle: queda en el log, correlacionable por traceId.
    this.logger.error({ message: 'Unexpected error', traceId }, exception instanceof Error ? exception.stack : String(exception));
    return { status: HttpStatus.INTERNAL_SERVER_ERROR, code: ErrorCode.INTERNAL_ERROR, message: 'Internal error' };
  }
}
