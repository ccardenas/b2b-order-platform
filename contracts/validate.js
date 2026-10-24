#!/usr/bin/env node
// Valida los fixtures de fixtures/ contra los JSON Schema de events/ y http/,
// y contra los component schemas definidos en los OpenAPI de http/.
// Cada fixture en <grupo>/valid/ debe pasar; cada uno en <grupo>/invalid/ debe fallar.

import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { load as loadYaml } from "js-yaml";

const here = dirname(fileURLToPath(import.meta.url));
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

let checks = 0;
let failures = 0;

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function listJsonFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith(".json"))
    .map((name) => join(dir, name));
}

function check(validateFn, file, expectValid) {
  checks++;
  const data = readJson(file);
  const valid = validateFn(data);
  const label = relative(here, file);
  if (valid === expectValid) {
    console.log(`  ok   ${label}`);
    return;
  }
  failures++;
  const expected = expectValid ? "valido" : "invalido";
  const got = valid ? "valido" : "invalido";
  console.error(`  FAIL ${label} (se esperaba ${expected}, resulto ${got})`);
  if (!valid && validateFn.errors) {
    for (const err of validateFn.errors) {
      console.error(`         ${err.instancePath || "/"} ${err.message}`);
    }
  }
}

function validateFixtureSet(title, validateFn, fixturesDir) {
  console.log(`\n== ${title} ==`);
  for (const file of listJsonFiles(join(fixturesDir, "valid"))) {
    check(validateFn, file, true);
  }
  for (const file of listJsonFiles(join(fixturesDir, "invalid"))) {
    check(validateFn, file, false);
  }
}

function componentSchema(openapiRelativePath, componentName) {
  const doc = loadYaml(readFileSync(join(here, openapiRelativePath), "utf8"));
  const schema = doc?.components?.schemas?.[componentName];
  if (!schema) {
    throw new Error(`No se encontro components.schemas.${componentName} en ${openapiRelativePath}`);
  }
  return schema;
}

// 1. Eventos (Kafka)
const ordersCreatedSchema = readJson(join(here, "events/orders.created.v1.schema.json"));
const ordersProcessedSchema = readJson(join(here, "events/orders.processed.v1.schema.json"));
validateFixtureSet(
  "orders.created.v1",
  ajv.compile(ordersCreatedSchema),
  join(here, "fixtures/events/orders.created.v1")
);
validateFixtureSet(
  "orders.processed.v1",
  ajv.compile(ordersProcessedSchema),
  join(here, "fixtures/events/orders.processed.v1")
);

// 2. Contrato de error compartido (HTTP)
const errorSchema = readJson(join(here, "http/error.schema.json"));
ajv.addSchema(errorSchema, errorSchema.$id);
validateFixtureSet("error HTTP compartido", ajv.getSchema(errorSchema.$id), join(here, "fixtures/http/error"));

// 3. Component schemas de los OpenAPI
const productSchema = componentSchema("http/products-api.openapi.yaml", "Product");
validateFixtureSet("products-api: Product", ajv.compile(productSchema), join(here, "fixtures/http/products"));

const clientSchema = componentSchema("http/clients-api.openapi.yaml", "Client");
validateFixtureSet("clients-api: Client", ajv.compile(clientSchema), join(here, "fixtures/http/clients"));

console.log(`\n${checks} fixtures verificadas, ${failures} fallo(s).`);
if (failures > 0) {
  process.exit(1);
}
