import { Client } from './client';
import { ClientNotFoundError } from './client.errors';
import { ClientsRepository } from './clients.repository';
import { ClientsService } from './clients.service';

describe('ClientsService', () => {
  const client: Client = {
    clientId: 'CLI-1',
    name: 'Cliente de prueba',
    status: 'ACTIVE',
    segment: 'WHOLESALE',
    taxRegime: 'GENERAL',
    market: 'MX',
  };

  let repository: jest.Mocked<ClientsRepository>;
  let service: ClientsService;

  beforeEach(() => {
    repository = { findById: jest.fn() };
    service = new ClientsService(repository);
  });

  it('devuelve el cliente que encuentra el repositorio', async () => {
    repository.findById.mockResolvedValue(client);

    await expect(service.getById('CLI-1')).resolves.toEqual(client);
    expect(repository.findById).toHaveBeenCalledWith('CLI-1');
  });

  it('lanza ClientNotFoundError si el repositorio no lo encuentra', async () => {
    repository.findById.mockResolvedValue(undefined);

    const error = await service.getById('CLI-404').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ClientNotFoundError);
    expect(error).toMatchObject({ clientId: 'CLI-404', message: 'Client CLI-404 not found' });
  });

  it('propaga los errores del repositorio sin traducirlos', async () => {
    const failure = new Error('storage down');
    repository.findById.mockRejectedValue(failure);

    await expect(service.getById('CLI-1')).rejects.toBe(failure);
  });

  it.each<[keyof Client, string]>([
    ['status', 'BLOCKED'],
    ['segment', 'RETAIL'],
    ['taxRegime', 'EXEMPT'],
  ])('no aplica reglas de elegibilidad: un cliente con %s=%s se devuelve igual', async (field, value) => {
    const stored = { ...client, [field]: value } as Client;
    repository.findById.mockResolvedValue(stored);

    await expect(service.getById('CLI-1')).resolves.toEqual(stored);
  });
});
