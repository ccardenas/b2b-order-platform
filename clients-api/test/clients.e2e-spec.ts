import { INestApplication, LoggerService } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { AppOptions, configureApp } from '../src/app.setup';
import { CLIENTS_REPOSITORY, ClientsRepository } from '../src/clients/clients.repository';
import { SEED_CLIENTS } from '../src/clients/clients.seed';
import { InMemoryClientsRepository } from '../src/clients/in-memory-clients.repository';
import { TRACE_HEADER } from '../src/common/trace';
import { readFixture, validateClient, validateError, validateHealth } from './contract';

// Guarda lo que la app loguea, para verificar el access log sin leer stdout.
class CapturingLogger implements LoggerService {
  readonly entries: { message: unknown; context?: string }[] = [];

  log(message: unknown, context?: string): void {
    this.entries.push({ message, context });
  }
  error(): void {}
  warn(): void {}

  accessLog(): Record<string, unknown>[] {
    return this.entries.filter((e) => e.context === 'HTTP').map((e) => e.message as Record<string, unknown>);
  }
}

interface Overrides {
  repository?: ClientsRepository;
  logger?: LoggerService;
}

async function createApp(options: AppOptions, overrides: Overrides = {}): Promise<INestApplication> {
  let builder = Test.createTestingModule({ imports: [AppModule] });
  if (overrides.repository) {
    builder = builder.overrideProvider(CLIENTS_REPOSITORY).useValue(overrides.repository);
  }
  const app = (await builder.compile()).createNestApplication({ logger: overrides.logger ?? false });
  configureApp(app, options);
  await app.init();
  return app;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function expectErrorContract(body: unknown, code: string): void {
  expect(validateError(body)).toBe(true);
  expect(body).toMatchObject({ error: { code } });
}

describe('clients-api (e2e)', () => {
  let app: INestApplication;
  const logger = new CapturingLogger();

  beforeAll(async () => {
    app = await createApp({ faultInjectionEnabled: false }, { logger });
  });

  afterAll(async () => {
    await app.close();
  });

  describe('GET /clients/:clientId', () => {
    it.each(['active-wholesale-general.json', 'blocked-retail-simplified.json'])(
      '200: responde igual que el fixture del contrato %s',
      async (fixture) => {
        const expected = readFixture(`http/clients/valid/${fixture}`) as { clientId: string };

        const res = await request(app.getHttpServer()).get(`/clients/${expected.clientId}`).expect(200);

        expect(res.headers['content-type']).toMatch(/application\/json/);
        expect(res.body).toEqual(expected);
        expect(validateClient(res.body)).toBe(true);
      },
    );

    it('404 CLIENT_NOT_FOUND con el contrato de error y el traceId del header', async () => {
      const res = await request(app.getHttpServer()).get('/clients/CLI-99999').expect(404);

      expectErrorContract(res.body, 'CLIENT_NOT_FOUND');
      expect(res.body.error.message).toBe('Client CLI-99999 not found');
      expect(res.body.error.traceId).toBe(res.headers['x-trace-id']);
      expect(res.body.error.traceId).toMatch(/^[0-9a-f]{32}$/);
    });

    it('propaga el X-Trace-Id recibido', async () => {
      const res = await request(app.getHttpServer()).get('/clients/CLI-99999').set(TRACE_HEADER, 'trace-abc').expect(404);

      expect(res.headers['x-trace-id']).toBe('trace-abc');
      expect(res.body.error.traceId).toBe('trace-abc');
    });

    it.each([
      ['caracteres fuera del patron', 'CLI%2499'],
      ['espacios', 'CLI%2099'],
      ['mas de 64 caracteres', 'C'.repeat(65)],
    ])('400 INVALID_REQUEST con %s', async (_case, clientId) => {
      const res = await request(app.getHttpServer()).get(`/clients/${clientId}`).expect(400);

      expectErrorContract(res.body, 'INVALID_REQUEST');
      expect(res.body.error.message).toContain('clientId must match');
    });

    it('sin inyeccion de fallos, los IDs de fallo son clientes inexistentes', async () => {
      const res = await request(app.getHttpServer()).get('/clients/CLI-FAIL-429').expect(404);

      expectErrorContract(res.body, 'CLIENT_NOT_FOUND');
    });
  });

  describe('rutas inexistentes', () => {
    it.each([
      ['GET', '/nope'],
      ['POST', '/clients/CLI-99821'],
    ])('%s %s -> 404 ROUTE_NOT_FOUND con el contrato de error', async (method, path) => {
      const res = await request(app.getHttpServer())[method.toLowerCase() as 'get' | 'post'](path).expect(404);

      expectErrorContract(res.body, 'ROUTE_NOT_FOUND');
      expect(res.body.error.message).toBe(`No route for ${method} ${path}`);
    });
  });

  describe('GET /health', () => {
    it('200 {"status":"UP"}', async () => {
      const res = await request(app.getHttpServer()).get('/health').expect(200);

      expect(res.body).toEqual({ status: 'UP' });
      expect(validateHealth(res.body)).toBe(true);
    });
  });

  describe('access log', () => {
    it('escribe una entrada por request con status, duracion y traceId', async () => {
      await request(app.getHttpServer()).get('/clients/CLI-99999?debug=1').set(TRACE_HEADER, 'trace-log').expect(404);

      expect(logger.accessLog()).toContainEqual({
        method: 'GET',
        path: '/clients/CLI-99999',
        query: 'debug=1',
        status: 404,
        durationMs: expect.any(Number),
        traceId: 'trace-log',
      });
    });
  });
});

describe('clients-api (e2e) con error inesperado del repositorio', () => {
  let app: INestApplication;

  beforeAll(async () => {
    app = await createApp(
      { faultInjectionEnabled: false },
      { repository: { findById: () => Promise.reject(new Error('connection refused to 10.0.0.5')) } },
    );
  });

  afterAll(async () => {
    await app.close();
  });

  it('500 INTERNAL_ERROR sin filtrar el detalle interno', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-99821').expect(500);

    expectErrorContract(res.body, 'INTERNAL_ERROR');
    expect(res.body.error.message).toBe('Internal error');
  });
});

describe('clients-api (e2e) con FAULT_INJECTION_ENABLED', () => {
  const slowDelayMs = 200;
  let app: INestApplication;
  const logger = new CapturingLogger();
  // Repositorio real envuelto en un espia: permite saber si el handler se ejecuto.
  const seedRepository = new InMemoryClientsRepository(SEED_CLIENTS);
  const findById = jest.fn((clientId: string) => seedRepository.findById(clientId));

  beforeAll(async () => {
    app = await createApp({ faultInjectionEnabled: true, slowDelayMs }, { repository: { findById }, logger });
  });

  afterAll(async () => {
    await app.close();
  });

  it('CLI-FAIL-429 -> 429 RATE_LIMITED con Retry-After: 1', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-FAIL-429').expect(429);

    expectErrorContract(res.body, 'RATE_LIMITED');
    expect(res.headers['retry-after']).toBe('1');
  });

  it('CLI-FAIL-500 -> 500 INTERNAL_ERROR', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-FAIL-500').expect(500);

    expectErrorContract(res.body, 'INTERNAL_ERROR');
  });

  it('CLI-BAD-ENUM -> 200 con un status fuera del contrato (S10)', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-BAD-ENUM').expect(200);

    expect(res.body.status).toBe('SUSPENDED');
    expect(validateClient(res.body)).toBe(false);
    expect(validateClient.errors).toEqual(
      expect.arrayContaining([expect.objectContaining({ instancePath: '/status', keyword: 'enum' })]),
    );
  });

  it('CLI-SLOW -> espera el delay y sigue al handler real (404)', async () => {
    const started = Date.now();
    const res = await request(app.getHttpServer()).get('/clients/CLI-SLOW').expect(404);

    expect(Date.now() - started).toBeGreaterThanOrEqual(slowDelayMs);
    expectErrorContract(res.body, 'CLIENT_NOT_FOUND');
  });

  it('CLI-SLOW: si el cliente corta antes, el handler no se ejecuta y el access log registra 499', async () => {
    findById.mockClear();

    const error = await request(app.getHttpServer())
      .get('/clients/CLI-SLOW')
      .timeout(50)
      .catch((e: { timeout?: number }) => e);
    expect(error).toMatchObject({ timeout: 50 });

    // Pasado el delay, sin cancelacion el handler ya habria consultado el repositorio.
    await sleep(slowDelayMs + 100);

    expect(findById).not.toHaveBeenCalled();
    expect(logger.accessLog()).toContainEqual(expect.objectContaining({ path: '/clients/CLI-SLOW', status: 499 }));
  });

  it('un cliente normal no se ve afectado', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-99821').expect(200);

    expect(validateClient(res.body)).toBe(true);
  });

  it('/health no se ve afectado', async () => {
    await request(app.getHttpServer()).get('/health').expect(200, { status: 'UP' });
  });
});
