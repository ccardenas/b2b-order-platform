import { Client } from '../client';

// DTO del contrato (components.schemas.Client). Mapeo explicito desde el
// dominio: un campo interno nuevo no se filtra al JSON publicado (§8.5).
export interface ClientResponse {
  clientId: string;
  name: string;
  status: string;
  segment: string;
  taxRegime: string;
  market: string;
}

export function toClientResponse(client: Client): ClientResponse {
  return {
    clientId: client.clientId,
    name: client.name,
    status: client.status,
    segment: client.segment,
    taxRegime: client.taxRegime,
    market: client.market,
  };
}
