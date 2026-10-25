// Modelo de dominio del cliente. status, segment y taxRegime son tres campos
// independientes (enunciado §6), no valores de un unico enum.

export type ClientStatus = 'ACTIVE' | 'BLOCKED';
export type ClientSegment = 'RETAIL' | 'WHOLESALE';
export type TaxRegime = 'GENERAL' | 'SIMPLIFIED' | 'EXEMPT';
export type Market = 'MX' | 'CO' | 'PE';

export interface Client {
  readonly clientId: string;
  readonly name: string;
  readonly status: ClientStatus;
  readonly segment: ClientSegment;
  readonly taxRegime: TaxRegime;
  readonly market: Market;
}
