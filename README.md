# b2b-order-platform

Prueba técnica Tech Lead | Grupo Mariposa

Plataforma de pedidos B2B para distribuidores de MX, CO y PE. Un worker consume
pedidos de Kafka, los enriquece con dos APIs, calcula descuentos e impuestos, y
persiste y publica **un único resultado por revisión del pedido**, con
duplicados, eventos fuera de orden y caídas parciales de por medio.

```
Kafka (orders.created.v1) → order-processor (Java) → clients-api (NestJS)
                                                  → products-api (Go)
                                                  → MongoDB (orders + outbox embebido)
                                                  → Kafka (orders.processed.v1 / orders.processing.dlt)
```

| Carpeta | Qué es |
|---|---|
| [`order-processor/`](order-processor) | Worker en Java 21 / Spring Boot 3.5: dominio, idempotencia, outbox, DLT, observabilidad |
| [`products-api/`](products-api) | Catálogo de productos por mercado. Go, solo librería estándar |
| [`clients-api/`](clients-api) | Maestro de clientes. NestJS, TypeScript `strict` |
| [`contracts/`](contracts) | JSON Schema de los eventos, OpenAPI de las APIs y fixtures. Fuente única de contratos |
| [`docs/`](docs) | Propuesta de arquitectura, notas de implementación, liderazgo técnico y ADRs |

El diseño está en [`docs/architecture-proposal.md`](docs/architecture-proposal.md), escrito antes
de implementar. Las diferencias con lo implementado están en
[`docs/implementation-notes.md`](docs/implementation-notes.md). Las dos decisiones centrales tienen
su ADR en [`docs/adr/`](docs/adr).
Cómo conduciría la implementación con un equipo de cuatro personas está en
[`docs/technical-leadership.md`](docs/technical-leadership.md).

---

## Requisitos

Para levantar y probar el sistema completo alcanza con **Docker** y **Docker Compose v2**
(`docker compose`, no `docker-compose`). Todo lo demás corre en contenedores.

Para compilar y correr los tests de cada componente:

| Componente | Necesita |
|---|---|
| order-processor | **JDK 21 o superior** (un JRE no alcanza: hace falta `javac`). Maven viene con el wrapper (`./mvnw`). Docker para los tests de integración. |
| products-api | Go 1.22 o superior |
| clients-api | Node.js 22 o superior |
| contracts | Node.js 22 o superior |

Puertos que se usan en el host: `9094` (Kafka), `27017` (MongoDB), `8080` (products-api),
`3000` (clients-api) y `8081` (order-processor).

---

## Cómo correr

### Todo el stack

```bash
docker compose up -d --build --wait
```

`--wait` vuelve cuando todos los healthchecks están en verde (unos 20 s con las imágenes ya
descargadas; la primera vez tarda más por la descarga y el build). Levanta:

| Servicio | Desde el host | Notas |
|---|---|---|
| `kafka` | `localhost:9094` | Un nodo KRaft. Dentro de la red de compose es `kafka:9092`. |
| `kafka-init` | — | Crea los tres tópicos (3 particiones) y termina. El broker no crea tópicos automáticamente. |
| `mongo` | `mongodb://localhost:27017` | Standalone, sin autenticación. Base `b2b_orders`, colección `orders`. |
| `products-api` | `http://localhost:8080` | Con inyección de fallos activada. |
| `clients-api` | `http://localhost:3000` | Con inyección de fallos activada. |
| `order-processor` | `http://localhost:8081/actuator/health` | Métricas en `/actuator/prometheus`. |

Para apagar y borrar los datos:

```bash
docker compose down -v
```

### order-processor fuera de Docker

Útil para depurar desde el IDE. Los valores por defecto de `application.properties` apuntan a
los puertos de compose en el host:

```bash
docker compose stop order-processor          # libera el puerto 8081 y el consumer group
cd order-processor && ./mvnw spring-boot:run
```

### Las APIs por separado

```bash
cd products-api && PORT=8080 FAULT_INJECTION_ENABLED=true go run ./cmd/products-api
cd clients-api && npm ci && npm run build && PORT=3000 FAULT_INJECTION_ENABLED=true npm start

curl -s 'localhost:8080/products/PRD-001?market=MX'
curl -s localhost:3000/clients/CLI-99821
```

---

## Tests

| Componente | Comando | Qué cubre |
|---|---|---|
| contracts | `cd contracts && npm install && npm run validate` | Fixtures válidos e inválidos contra los JSON Schema y los schemas de los OpenAPI |
| order-processor | `cd order-processor && ./mvnw test` | Dominio (impuestos, descuento, redondeo, elegibilidad, validación), caso de uso con fakes, adaptadores HTTP con WireMock (404, 429, 5xx, timeout, enum desconocido), serialización, ArchUnit. **No necesita Docker.** |
| order-processor | `cd order-processor && ./mvnw verify` | Lo anterior más los `*IT` con Testcontainers (Kafka y MongoDB reales): escritura condicional con 10 hilos, `STALE`, `VERSION_CONFLICT`, flujo completo, DLT con headers, relay del outbox, logs y métricas. **Necesita Docker.** |
| products-api | `cd products-api && go vet ./... && go test -race ./...` | Servicio con repositorio fake, handler con `httptest`, inyección de fallos, cancelación de contexto |
| clients-api | `cd clients-api && npm ci && npm test && npm run test:e2e` | Service con repositorio mock; e2e con Supertest y respuestas validadas contra el OpenAPI |

Si `./mvnw` falla con `release version 21 not supported` o `No compiler is provided in this
environment`, el `JAVA_HOME` apunta a un JDK anterior a 21 o a un JRE. Apúntalo a un JDK ≥ 21.

---

## Probar el sistema a mano

Con el stack arriba. Todos los comandos se ejecutan desde la raíz del repositorio y usan las
herramientas que traen las imágenes, sin instalar nada en el host.

### 1. Publicar un evento de ejemplo

Esta función abre un productor dentro del contenedor de Kafka. Cada línea de entrada es
`clave|valor`, y la clave es el `orderId`:

```bash
produce() {
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka:9092 --topic orders.created.v1 \
    --reader-property parse.key=true --reader-property 'key.separator=|'
}
```

Un pedido mayorista de MX que se aprueba (es el fixture
[`approved-multi-item.json`](contracts/fixtures/events/orders.created.v1/valid/approved-multi-item.json)
en una línea):

```bash
echo 'ORD-MX-000147|{"eventId":"01J8ZP6M5E4RH0K7Y2N9A3TQWX","eventVersion":1,"occurredAt":"2026-09-18T15:42:10Z","orderId":"ORD-MX-000147","market":"MX","currency":"MXN","clientId":"CLI-99821","channel":"C1","items":[{"productId":"PRD-001","quantity":24,"unitPrice":35.5},{"productId":"PRD-008","quantity":12,"unitPrice":82.0}]}' | produce
```

Otros casos que conviene probar:

```bash
# Rechazado: cliente BLOCKED y producto DISCONTINUED
echo 'ORD-MX-000148|{"eventId":"EVT-README-0002","eventVersion":1,"occurredAt":"2026-09-18T15:43:00Z","orderId":"ORD-MX-000148","market":"MX","currency":"MXN","clientId":"CLI-40050","items":[{"productId":"PRD-004","quantity":5,"unitPrice":20.0}]}' | produce

# Fallo técnico: products-api responde 503 en los 3 intentos → TECHNICAL_FAILURE y DLT
echo 'ORD-MX-000149|{"eventId":"EVT-README-0003","eventVersion":1,"occurredAt":"2026-09-18T15:44:00Z","orderId":"ORD-MX-000149","market":"MX","currency":"MXN","clientId":"CLI-99821","items":[{"productId":"PRD-FAIL-503","quantity":1,"unitPrice":10.0}]}' | produce

# Inválido: moneda que no corresponde al mercado → DLT como VALIDATION, no se persiste
echo 'ORD-MX-000150|{"eventId":"EVT-README-0004","eventVersion":1,"orderId":"ORD-MX-000150","market":"MX","currency":"COP","clientId":"CLI-99821","items":[]}' | produce

# Duplicado: repetir el primer comando. No hay segundo documento ni segundo evento de salida.
```

Los IDs de fallo disponibles (solo con `FAULT_INJECTION_ENABLED=true`, como en compose):

| ID | Respuesta | Cómo lo clasifica order-processor |
|---|---|---|
| `PRD-FAIL-503`, `PRD-FAIL-500`, `CLI-FAIL-500` | 503 / 500 | `EXTERNAL_TRANSIENT`: 3 intentos y DLT |
| `PRD-FAIL-429`, `CLI-FAIL-429` | 429 con `Retry-After: 1` | `EXTERNAL_TRANSIENT`: respeta la espera, 3 intentos y DLT |
| `PRD-SLOW`, `CLI-SLOW` | Responde a los 5 s | Timeout de 2 s por intento: `EXTERNAL_TRANSIENT` |
| `PRD-BAD-ENUM`, `CLI-BAD-ENUM` | 200 con un `status` desconocido | `EXTERNAL_PERMANENT`: sin reintento, DLT |

Clientes y productos de la semilla: `clients-api/src/clients/clients.seed.ts` y
`products-api/internal/product/memory/seed.go`.

### 2. Seguir el pedido en los logs

Los logs son JSON, una línea por transición (`received`, `validated`, `enriched`, `evaluated`,
`persisted`, `published`, `dlt`, `duplicate`, `stale`), con `orderId`, `eventId` y
`eventVersion` como campos:

```bash
docker compose logs --no-log-prefix order-processor | grep '"orderId":"ORD-MX-000147"'
```

### 3. Verificar Mongo

```bash
docker compose exec -T mongo mongosh b2b_orders --quiet --eval '
  db.orders.find({}, {status: 1, eventVersion: 1, totals: 1, rejectionReasons: 1,
                      "technicalError.category": 1, "outbox.status": 1}).toArray()'
```

Lo esperado para los cuatro casos anteriores:

- `ORD-MX-000147`: `APPROVED`, `grandTotal` `Decimal128('2100.11')` y `outbox.status` `PUBLISHED`.
- `ORD-MX-000148`: `REJECTED`, `rejectionReasons` `['CLIENT_BLOCKED', 'PRODUCT_DISCONTINUED']`,
  `totals: null` y `outbox.status` `PUBLISHED`.
- `ORD-MX-000149`: `TECHNICAL_FAILURE` con `technicalError.category` `EXTERNAL_TRANSIENT` y sin
  outbox, porque un fallo técnico no se publica.
- `ORD-MX-000150`: no aparece. Un mensaje inválido no se persiste.

El documento completo de un pedido (líneas, snapshot del cliente, traza y payload del outbox):

```bash
docker compose exec -T mongo mongosh b2b_orders --quiet --eval 'db.orders.findOne({_id: "ORD-MX-000147"})'
```

### 4. Verificar el tópico de salida

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic orders.processed.v1 --from-beginning \
  --timeout-ms 10000 --formatter-property print.key=true 2>/dev/null
```

Debe haber un evento por cada pedido aprobado o rechazado, aunque el evento de entrada se haya
enviado varias veces. El `eventId` de salida es estable y `sourceEventId` referencia el evento
de entrada. Los importes conservan dos decimales (`"grandTotal":2100.11`, `"grossSubtotal":1836.00`).

### 5. Verificar la DLT

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic orders.processing.dlt --from-beginning \
  --timeout-ms 10000 --formatter-property print.headers=true --formatter-property print.key=true 2>/dev/null
```

Cada mensaje conserva la clave y los bytes originales, y agrega los headers `x-order-id`,
`x-event-id`, `x-error-category`, `x-error-cause` (sanitizada, sin stack trace),
`x-attempts`, `x-failed-at`, `x-component` y `x-original-topic` / `-partition` / `-offset`.

### 6. Métricas

```bash
curl -s localhost:8081/actuator/prometheus | grep -E '^(orders_|http_client_retries|outbox_pending)'
```

Tras los ejemplos anteriores (con el duplicado incluido) se ven, entre otras,
`orders_processed_total{market="MX",status="APPROVED"} 1.0`, `orders_duplicates_total 1.0` y
`orders_dlt_total{category="VALIDATION"} 1.0`.

---

## Variables de entorno

### order-processor

Cualquier propiedad de Spring se puede sobrescribir con una variable de entorno, en la forma de
*relaxed binding* de Spring Boot: mayúsculas, `.` → `_` y **sin** guiones
(`order-processor.http.clients-base-url` → `ORDERPROCESSOR_HTTP_CLIENTSBASEURL`). Los valores
por defecto son los de la propuesta (§5.3, §7.2).

| Variable | Por defecto | Qué controla |
|---|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Brokers de Kafka |
| `SPRING_KAFKA_CONSUMER_GROUPID` | `order-processor` | Consumer group |
| `SPRING_DATA_MONGODB_URI` | `mongodb://localhost:27017/b2b_orders?serverSelectionTimeoutMS=5000` | MongoDB. El timeout acota cuánto bloquea una escritura con Mongo caído. |
| `SERVER_PORT` | `8081` | Puerto de actuator |
| `ORDERPROCESSOR_HTTP_CLIENTSBASEURL` | `http://localhost:3000` | URL base de clients-api |
| `ORDERPROCESSOR_HTTP_PRODUCTSBASEURL` | `http://localhost:8080` | URL base de products-api |
| `ORDERPROCESSOR_HTTP_CONNECTTIMEOUT` | `500ms` | Connect timeout por llamada |
| `ORDERPROCESSOR_HTTP_READTIMEOUT` | `2s` | Timeout por intento |
| `ORDERPROCESSOR_HTTP_MAXATTEMPTS` | `3` | Intentos por llamada (1 + 2 reintentos) |
| `ORDERPROCESSOR_HTTP_INITIALBACKOFF` | `200ms` | Primera espera entre intentos |
| `ORDERPROCESSOR_HTTP_BACKOFFMULTIPLIER` | `4` | Multiplicador del backoff (200 ms → 800 ms, con jitter) |
| `ORDERPROCESSOR_HTTP_RETRYAFTERCAP` | `2s` | Tope para `Retry-After`. Si se supera, se da por agotado. |
| `ORDERPROCESSOR_ENRICHMENTTIMEOUT` | `12s` | Presupuesto total del enriquecimiento de un pedido |
| `ORDERPROCESSOR_KAFKA_INPUTTOPIC` | `orders.created.v1` | Tópico de entrada |
| `ORDERPROCESSOR_KAFKA_PROCESSEDTOPIC` | `orders.processed.v1` | Tópico de salida |
| `ORDERPROCESSOR_KAFKA_DEADLETTERTOPIC` | `orders.processing.dlt` | DLT |
| `ORDERPROCESSOR_KAFKA_CONCURRENCY` | `3` | Consumidores del listener (= particiones) |
| `ORDERPROCESSOR_KAFKA_MAXPOLLRECORDS` | `10` | Registros por poll |
| `ORDERPROCESSOR_KAFKA_PUBLISHTIMEOUT` | `10s` | Espera máxima de la publicación inmediata |
| `ORDERPROCESSOR_KAFKA_PERSISTENCEMAXATTEMPTS` | `6` | Entregas de un mensaje ante errores de persistencia antes de la DLT |
| `ORDERPROCESSOR_KAFKA_PERSISTENCEINITIALBACKOFF` | `2s` | Primera espera entre esas entregas |
| `ORDERPROCESSOR_KAFKA_PERSISTENCEBACKOFFMULTIPLIER` | `2` | Multiplicador (2, 4, 8, 16, 32 s) |
| `ORDERPROCESSOR_KAFKA_PERSISTENCEMAXBACKOFF` | `32s` | Espera máxima entre entregas |
| `ORDERPROCESSOR_OUTBOX_RELAYINTERVAL` | `5s` | Intervalo del `OutboxRelay` |
| `ORDERPROCESSOR_OUTBOX_MINAGE` | `10s` | Antigüedad mínima de un evento `PENDING` para que lo tome el relay |
| `ORDERPROCESSOR_OUTBOX_BATCHSIZE` | `100` | Eventos por pasada del relay |

### products-api y clients-api

| Variable | Por defecto | Qué controla |
|---|---|---|
| `PORT` | `8080` (products-api) / `3000` (clients-api) | Puerto HTTP |
| `FAULT_INJECTION_ENABLED` | `false` | Activa los IDs de fallo de la tabla anterior. Solo compose y tests lo activan. |

No hay secretos ni credenciales: Mongo y Kafka corren sin autenticación en el entorno local.

---

## Limitaciones conocidas

Lo que esta versión **no** resuelve, de forma deliberada o por alcance. El detalle y el motivo
de cada punto están en [`docs/implementation-notes.md`](docs/implementation-notes.md).

- **Seguridad:** no hay autenticación entre servicios (supuesto S11 de la propuesta). Kafka va en
  texto plano y Mongo no tiene usuario. Sirve solo para desarrollo local.
- **Un solo broker y un Mongo standalone:** factor de replicación 1. No se probó la pérdida de un
  nodo.
- **Relay sin lease:** con varias instancias, el `OutboxRelay` puede publicar dos veces el mismo
  evento. Lleva el mismo `eventId` y los consumidores deben deduplicar por él (publicación al
  menos una vez).
- **Revisión pendiente reemplazada:** si llega una revisión nueva mientras el evento de la
  anterior sigue `PENDING`, el evento anterior nunca se publica. Es el trade-off asumido en §7.3.
- **Healthcheck de liveness:** `/actuator/health` no comprueba Mongo ni Kafka. Si Mongo no está
  disponible al arrancar, la aplicación no arranca y compose la reinicia.
- **Read timeout:** el timeout por intento cubre hasta recibir los headers, no la lectura del
  cuerpo (limitación del `HttpClient` del JDK). El presupuesto total del enriquecimiento acota
  el peor caso.
- **Replay desde la DLT:** los headers tienen lo necesario, pero no hay herramienta de replay. Se
  hace a mano.
- **Sin CI:** los tests se corren localmente. No hay job que verifique la compatibilidad hacia
  atrás de los schemas (§8.3) ni contract tests dirigidos por el consumidor.
- **Sin script de smoke E2E:** la sección "Probar el sistema a mano" lo reemplaza.
- **Datos en memoria:** las APIs usan semillas fijas y no hay caché de productos en
  order-processor.

---

## Uso de herramientas de IA

### Herramientas y para qué se usaron

| Herramienta | Uso |
|---|---|
| **Claude** (chat web) | Discusión y redacción de la propuesta de arquitectura antes de implementar. |
| **Claude Code** (agente en la terminal) | Implementación de los tres servicios, los contratos, `docker-compose.yml`, los tests y la documentación, hito por hito según el plan de la propuesta (§12). |

Claude Code trabajó con las reglas de [`CLAUDE.md`](CLAUDE.md): sin commits propios, un hito a la
vez con los tests en verde antes de avanzar, explicación de cada decisión no obvia y parada
obligatoria ante cualquier ambigüedad o contradicción con la propuesta. Cada hito se commiteó
a mano. Las diferencias con la propuesta quedaron registradas en
[`docs/implementation-notes.md`](docs/implementation-notes.md), con la fecha en que se
aprobaron cuando cambiaban un contrato (P1, C1, C2).

### Qué se verificó personalmente

- **La propuesta de arquitectura** ([`docs/architecture-proposal.md`](docs/architecture-proposal.md)):
  supuestos, clasificación de errores, idempotencia, consistencia entre Mongo y Kafka y plan
  de entrega.
- **El cálculo de importes:** impuestos por mercado y categoría, exención por régimen, descuento
  mayorista y redondeo `HALF_UP` por importe, contrastados con los tests del dominio.
- **El stack completo:** levantado con `docker compose`, con los tests de los tres servicios y la
  prueba manual de [Probar el sistema a mano](#probar-el-sistema-a-mano).

El resto del código (adaptadores, persistencia, listener, outbox y observabilidad) lo generó
Claude Code. Su comportamiento está respaldado por los tests automatizados descritos en
[Tests](#tests): WireMock para los fallos HTTP, Testcontainers para la concurrencia y el flujo
completo, ArchUnit para los límites del dominio.

### Una sugerencia descartada

Al implementar clients-api, Claude Code propuso agregar un fallo inyectado `CLI-FAIL-503`,
simétrico al `PRD-FAIL-503` de products-api. Se descartó: el OpenAPI de clients-api no declara
un 503, así que agregarlo obligaba a ampliar el contrato solo para un fallo de prueba. Además,
el caso transitorio ya estaba cubierto por `CLI-FAIL-500` y `CLI-FAIL-429`, que order-processor
clasifica igual que un 503. Quedó registrado en la nota C2 de
[`docs/implementation-notes.md`](docs/implementation-notes.md) y en
[`docs/ai-usage-log.md`](docs/ai-usage-log.md).
