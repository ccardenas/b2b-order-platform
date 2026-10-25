import { Inject, Injectable } from '@nestjs/common';
import { Client } from './client';
import { ClientNotFoundError } from './client.errors';
import { CLIENTS_REPOSITORY, ClientsRepository } from './clients.repository';

@Injectable()
export class ClientsService {
  constructor(@Inject(CLIENTS_REPOSITORY) private readonly repository: ClientsRepository) {}

  async getById(clientId: string): Promise<Client> {
    const client = await this.repository.findById(clientId);
    if (!client) {
      throw new ClientNotFoundError(clientId);
    }
    return client;
  }
}
