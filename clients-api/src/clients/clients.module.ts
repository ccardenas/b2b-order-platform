import { Module } from '@nestjs/common';
import { ClientsController } from './clients.controller';
import { CLIENTS_REPOSITORY } from './clients.repository';
import { SEED_CLIENTS } from './clients.seed';
import { ClientsService } from './clients.service';
import { InMemoryClientsRepository } from './in-memory-clients.repository';

@Module({
  controllers: [ClientsController],
  providers: [
    ClientsService,
    { provide: CLIENTS_REPOSITORY, useFactory: () => new InMemoryClientsRepository(SEED_CLIENTS) },
  ],
})
export class ClientsModule {}
