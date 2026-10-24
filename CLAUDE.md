# CLAUDE.md — b2b-order-platform

Contexto para Claude Code. La fuente de verdad del diseño es `docs/architecture-proposal.md`: **léela antes de proponer cambios** y no contradigas sus decisiones sin avisarlo explícitamente.

## Qué es este repositorio

Prueba técnica para Tech Lead: plataforma distribuida de pedidos B2B (MX, CO, PE).

```
Kafka (orders.created.v1) → order-processor (Java) → clients-api (NestJS)
                                                  → products-api (Go)
                                                  → MongoDB (orders + outbox embebido)
                                                  → Kafka (orders.processed.v1 / orders.processing.dlt)
```

| Carpeta | Stack | Profundidad esperada |
|---|---|---|
| `order-processor/` | Java 21, Spring Boot 3.x, Maven (wrapper), JUnit 5, Testcontainers, ArchUnit, WireMock | Núcleo: dominio, idempotencia, consistencia, resiliencia |
| `products-api/` | Go 1.22+, solo librería estándar (`net/http`) | Pequeña, idiomática, `context.Context`, tests table-driven |
| `clients-api/` | NestJS, TypeScript `strict`, class-validator, Jest + Supertest | Pequeña, modular, validación explícita |
| `contracts/` | JSON Schema (eventos) + OpenAPI (HTTP) | Fuente única de contratos |
| `docs/` | Markdown | Propuesta, notas de implementación, liderazgo técnico, ADRs |

## Reglas de trabajo (obligatorias)

1. **No hagas commits ni push.** Claudio revisa y commitea. Al terminar una tarea, sugiere un mensaje de commit en formato Conventional Commits (`feat(order-processor): ...`).
2. **Trabaja por hitos pequeños** (ver "Plan de hitos"). No avances al siguiente sin que el actual compile y tenga los tests en verde.
3. **Ejecuta los tests** después de cada cambio relevante y muestra el resultado. No declares algo terminado sin evidencia.
4. **Explica las decisiones no obvias** en 2 o 3 líneas. Todo el código debe poder defenderse en una entrevista técnica.
5. **Simplicidad primero.** Nada de abstracciones, patrones ni dependencias que no resuelvan un problema concreto descrito en la propuesta.
6. Si encuentras una ambigüedad o algo que contradice la propuesta, **detente y pregunta**. No lo resuelvas en silencio.
7. Las diferencias reales respecto de la propuesta se anotan en `docs/implementation-notes.md`.
8. No escribas secretos ni credenciales reales en ningún archivo.

## Reglas de diseño (no negociables)

### order-processor
- Paquetes `domain/`, `application/` (con `port/in` y `port/out`) e `infrastructure/` (kafka, http, mongo, outbox, config).
- `domain/` es Java puro: **sin** Spring, Kafka, Mongo, Jackson ni DTOs externos. Lo verifica un test de ArchUnit.
- Modelos separados para DTO externo, dominio y documento Mongo, con **mapeos explícitos**.
- Dinero: `BigDecimal` en todas las capas, escala 2, `RoundingMode.HALF_UP` por importe. Los totales del pedido son la suma de los importes de línea ya redondeados. Jackson con `USE_BIG_DECIMAL_FOR_FLOATS`. Mongo con `Decimal128`.
- Orden de cálculo por línea: `gross = qty*price`, `discount = gross*rate`, `net = gross-discount`, `tax = net*taxRate`, `total = net+tax`.
- Descuento: 3% para WHOLESALE con 20 unidades o más **en la línea**.
- Tasas: MX 16/8/0, CO 19/5/0, PE 18/10/0 (STANDARD/REDUCED/EXEMPT). Un cliente con `taxRegime=EXEMPT` paga 0%.
- Concurrencia: listener bloqueante con virtual threads (`spring.threads.virtual.enabled=true`). Llamadas a productos en paralelo.
- Idempotencia: `_id = orderId` y **upsert condicional** (filtro `$or`: versión menor guardada, o misma versión, mismo eventId y `TECHNICAL_FAILURE`). Un E11000 se trata como "no aplicado" y se clasifica en `DUPLICATE`, `STALE` o `VERSION_CONFLICT`. **Nunca** usar `exists` + `save` como garantía.
- Outbox embebido en el mismo documento (`outbox.status PENDING/PUBLISHED`), publicación inmediata y `OutboxRelay` programado como respaldo. El `eventId` de salida es estable.
- Offsets: auto-commit desactivado, `AckMode.RECORD`, commit solo tras un estado durable.
- Errores y DLT: según la tabla §5.2 y los headers §5.5 de la propuesta.
- Logs JSON con `orderId`, `eventId` y `eventVersion` en el MDC. Una línea por transición.

### products-api (Go)
- `cmd/products-api`, `internal/product` (servicio, interfaz de repositorio, errores), `internal/product/memory`, `internal/httpapi`, `internal/faults`.
- El handler traduce errores de dominio a HTTP. El servicio no conoce HTTP.
- Contexto propagado hasta el repositorio. Shutdown con `signal.NotifyContext` + `server.Shutdown`.
- Semilla: al menos 10 productos repartidos entre MX, CO y PE, con todos los status y taxCategory.

### clients-api (NestJS)
- Controller solo como transporte. El service contiene la lógica. El repositorio va detrás de un token de inyección, con implementación en memoria.
- Cliente: `status` (`ACTIVE`/`BLOCKED`), `segment` (`RETAIL`/`WHOLESALE`) y `taxRegime` (`GENERAL`/`SIMPLIFIED`/`EXEMPT`) son tres campos independientes (según el enunciado, `docs/prueba tecnica Tech Lead.pdf` §6), no un único enum.
- Semilla: al menos 6 clientes repartidos entre MX, CO y PE, cubriendo ambos valores de `status` y `segment`, y los tres de `taxRegime` (incluido `SIMPLIFIED`, sin regla de negocio propia pero presente en el contrato).

### Compartido
- Contrato de error: `{"error":{"code","message","traceId"}}`. Códigos estables.
- Inyección de fallos solo con `FAULT_INJECTION_ENABLED=true`, por IDs semilla documentados (`PRD-FAIL-503`, `PRD-SLOW`, `CLI-FAIL-429`, `CLI-BAD-ENUM`, etc.). Debe vivir fuera del dominio y del service.
- Healthcheck `GET /health` en ambas APIs.

## Comandos

> Actualizar esta sección a medida que existan.

> `order-processor` necesita un **JDK 21** (no alcanza con un JRE: `javac` tiene
> que existir). Si `./mvnw` falla con `error: release version 21 not supported`,
> es porque solo hay JRE instalado — hace falta `JAVA_HOME` apuntando a un JDK 21.

```bash
# contracts (JSON Schema + OpenAPI + fixtures)
cd contracts && npm install && npm run validate

# order-processor
cd order-processor && ./mvnw verify

# products-api
cd products-api && go vet ./... && go test ./...

# clients-api
cd clients-api && npm ci && npm test && npm run test:e2e

# todo el stack
docker compose up -d --build
```

## Plan de hitos

| # | Hito | Terminado cuando |
|---|---|---|
| 0 | Estructura del repo, `.gitignore`, `contracts/` (JSON Schema + OpenAPI + fixtures) | Fixtures validados contra los schemas |
| 1 | Dominio de order-processor + tests unitarios + ArchUnit | `./mvnw test` en verde |
| 2 | products-api completa | `go test ./...` en verde |
| 3 | clients-api completa | `npm test` y e2e en verde |
| 4 | `docker-compose.yml` (Kafka KRaft, Mongo, APIs) | `docker compose up` con healthchecks en verde |
| 5 | Caso de uso + adaptadores HTTP (timeouts, retry, clasificación) | Tests con WireMock |
| 6 | Adaptador Mongo con escritura condicional + índices | Tests concurrentes con Testcontainers |
| 7 | Listener Kafka, offsets, DLT, outbox y relay | Test de integración del flujo completo |
| 8 | Observabilidad (logs JSON, Micrometer) | Métricas en `/actuator/prometheus` |
| 9 | README, implementation-notes, technical-leadership, ADRs, sección de IA | Un tercero levanta y prueba el sistema con el README |

## Registro de uso de IA

El README debe declarar qué herramientas se usaron, para qué, qué se verificó y una sugerencia descartada. Cuando Claudio rechace o corrija una propuesta tuya, **agrega una entrada** en `docs/ai-usage-log.md` con la fecha, qué se propuso, por qué se descartó y qué se hizo en su lugar.
