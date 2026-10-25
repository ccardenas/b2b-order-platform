// Error de dominio: no conoce HTTP. La traduccion a 404 CLIENT_NOT_FOUND la
// hace common/http-exception.filter.ts.
export class ClientNotFoundError extends Error {
  constructor(readonly clientId: string) {
    super(`Client ${clientId} not found`);
    this.name = 'ClientNotFoundError';
  }
}
