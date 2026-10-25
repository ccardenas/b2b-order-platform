import { Client } from './client';

// Token de inyeccion: el service depende de la interfaz, no de la semilla en
// memoria. Reemplazarla por una base de datos no toca controller ni service.
export const CLIENTS_REPOSITORY = Symbol('CLIENTS_REPOSITORY');

export interface ClientsRepository {
  // Asincrono aunque la implementacion actual sea en memoria, para que el
  // contrato ya sirva a un repositorio con I/O real.
  findById(clientId: string): Promise<Client | undefined>;
}
