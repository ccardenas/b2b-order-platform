// El cliente cerro la conexion antes de la respuesta. No es un error del
// servicio: el filtro no escribe nada y el access log lo registra como 499.
export class ClientClosedRequestError extends Error {
  constructor() {
    super('Client closed request');
    this.name = 'ClientClosedRequestError';
  }
}
