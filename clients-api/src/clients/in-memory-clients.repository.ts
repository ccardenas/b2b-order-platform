import { Client } from './client';
import { ClientsRepository } from './clients.repository';

export class InMemoryClientsRepository implements ClientsRepository {
  private readonly clients: ReadonlyMap<string, Client>;

  constructor(seed: readonly Client[]) {
    this.clients = new Map(seed.map((client) => [client.clientId, client]));
  }

  async findById(clientId: string): Promise<Client | undefined> {
    const client = this.clients.get(clientId);
    // Copia: un llamador que mute el resultado no altera la semilla.
    return client ? { ...client } : undefined;
  }
}
