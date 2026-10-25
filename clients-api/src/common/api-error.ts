import { HttpException, HttpStatus } from '@nestjs/common';

// Codigos estables del contrato de error (propuesta §4.4). El consumidor
// decide por status HTTP + code, nunca por message.
export const ErrorCode = {
  CLIENT_NOT_FOUND: 'CLIENT_NOT_FOUND',
  INVALID_REQUEST: 'INVALID_REQUEST',
  ROUTE_NOT_FOUND: 'ROUTE_NOT_FOUND',
  RATE_LIMITED: 'RATE_LIMITED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;

export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

// Excepcion HTTP que ya trae su code del contrato. La usan el ValidationPipe
// y la inyeccion de fallos; el filtro la escribe tal cual.
export class ApiError extends HttpException {
  constructor(
    status: HttpStatus,
    readonly code: ErrorCode,
    message: string,
  ) {
    super(message, status);
  }
}
