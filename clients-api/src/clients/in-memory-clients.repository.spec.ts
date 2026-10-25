import { SEED_CLIENTS } from './clients.seed';
import { CLIENT_ID_PATTERN } from './dto/client-id.params';
import { InMemoryClientsRepository } from './in-memory-clients.repository';

describe('InMemoryClientsRepository', () => {
  const repository = new InMemoryClientsRepository(SEED_CLIENTS);

  it('encuentra un cliente de la semilla por id', async () => {
    await expect(repository.findById('CLI-99821')).resolves.toMatchObject({ clientId: 'CLI-99821', market: 'MX' });
  });

  it('devuelve undefined para un id inexistente', async () => {
    await expect(repository.findById('CLI-99999')).resolves.toBeUndefined();
  });

  it('devuelve una copia: mutar el resultado no altera la semilla', async () => {
    const found = await repository.findById('CLI-99821');
    (found as { name: string }).name = 'mutado';

    await expect(repository.findById('CLI-99821')).resolves.toMatchObject({ name: 'Distribuidora Central' });
  });
});

describe('SEED_CLIENTS', () => {
  const values = <K extends keyof (typeof SEED_CLIENTS)[number]>(key: K) => new Set(SEED_CLIENTS.map((c) => c[key]));

  it('tiene al menos 6 clientes con ids unicos y validos segun el contrato', () => {
    expect(SEED_CLIENTS.length).toBeGreaterThanOrEqual(6);
    expect(values('clientId').size).toBe(SEED_CLIENTS.length);
    for (const { clientId } of SEED_CLIENTS) {
      expect(clientId).toMatch(CLIENT_ID_PATTERN);
    }
  });

  it('cubre todos los valores de market, status, segment y taxRegime', () => {
    expect(values('market')).toEqual(new Set(['MX', 'CO', 'PE']));
    expect(values('status')).toEqual(new Set(['ACTIVE', 'BLOCKED']));
    expect(values('segment')).toEqual(new Set(['RETAIL', 'WHOLESALE']));
    expect(values('taxRegime')).toEqual(new Set(['GENERAL', 'SIMPLIFIED', 'EXEMPT']));
  });

  it.each(['MX', 'CO', 'PE'])('el mercado %s tiene clientes ACTIVE y BLOCKED', (market) => {
    const statuses = new Set(SEED_CLIENTS.filter((c) => c.market === market).map((c) => c.status));
    expect(statuses).toEqual(new Set(['ACTIVE', 'BLOCKED']));
  });
});
