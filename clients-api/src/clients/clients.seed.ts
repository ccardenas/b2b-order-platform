import { Client } from './client';

// Semilla en memoria. Cada mercado tiene clientes ACTIVE y BLOCKED; entre
// todos cubren ambos segment y los tres taxRegime (SIMPLIFIED no tiene regla
// de negocio propia, pero esta en el contrato).
//
// CLI-99821 y CLI-10042 coinciden con contracts/fixtures/http/clients/valid y
// con los fixtures de orders.created.v1. CLI-99999 queda fuera a proposito:
// sirve como ejemplo de 404.
export const SEED_CLIENTS: readonly Client[] = [
  // MX
  { clientId: 'CLI-99821', name: 'Distribuidora Central', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'MX' },
  { clientId: 'CLI-20031', name: 'Abarrotes del Norte', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'EXEMPT', market: 'MX' },
  { clientId: 'CLI-40050', name: 'Tiendas del Bajio', status: 'BLOCKED', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'MX' },
  // CO
  { clientId: 'CLI-10042', name: 'Comercial Andina', status: 'BLOCKED', segment: 'RETAIL', taxRegime: 'SIMPLIFIED', market: 'CO' },
  { clientId: 'CLI-30017', name: 'Mayorista del Caribe', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'EXEMPT', market: 'CO' },
  // PE
  { clientId: 'CLI-55210', name: 'Distribuidora Lima Norte', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'PE' },
  { clientId: 'CLI-60088', name: 'Bodega San Martin', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'SIMPLIFIED', market: 'PE' },
  { clientId: 'CLI-70120', name: 'Comercial Arequipa', status: 'BLOCKED', segment: 'RETAIL', taxRegime: 'EXEMPT', market: 'PE' },
];
