// Validadores de las respuestas contra contracts/ (propuesta §8.3 y §10): los
// e2e fallan si la API se desvia del OpenAPI o del contrato de error.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import Ajv2020, { ValidateFunction } from 'ajv/dist/2020';
import { load as loadYaml } from 'js-yaml';

const contractsDir = resolve(__dirname, '../../contracts');

interface OpenApiDoc {
  paths: Record<string, Record<string, { responses: Record<string, { content: Record<string, { schema: object }> }> }>>;
  components: { schemas: Record<string, object> };
}

const openapi = loadYaml(readFileSync(resolve(contractsDir, 'http/clients-api.openapi.yaml'), 'utf8')) as OpenApiDoc;
const errorSchema = JSON.parse(readFileSync(resolve(contractsDir, 'http/error.schema.json'), 'utf8')) as { $id: string };

const ajv = new Ajv2020({ allErrors: true, strict: false });
ajv.addSchema(errorSchema);

export const validateClient: ValidateFunction = ajv.compile(openapi.components.schemas.Client);
export const validateError: ValidateFunction = ajv.compile({ $ref: errorSchema.$id });
export const validateHealth: ValidateFunction = ajv.compile(
  openapi.paths['/health'].get.responses['200'].content['application/json'].schema,
);

export function readFixture(relativePath: string): unknown {
  return JSON.parse(readFileSync(resolve(contractsDir, 'fixtures', relativePath), 'utf8'));
}
