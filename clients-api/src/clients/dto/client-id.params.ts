import { Matches } from 'class-validator';

// Mismo patron que parameters.clientId en contracts/http/clients-api.openapi.yaml.
export const CLIENT_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

export class ClientIdParams {
  @Matches(CLIENT_ID_PATTERN, { message: `clientId must match ${CLIENT_ID_PATTERN.source}` })
  clientId!: string;
}
