# Propuesta de arquitectura — Plataforma confiable de pedidos B2B

> Documento escrito **antes** de iniciar la implementación. Las diferencias que surjan durante el desarrollo se registran en `docs/implementation-notes.md`, no se reescriben aquí.

---

## 1. Entendimiento del problema

Distribuidores de MX, CO y PE generan pedidos que llegan como eventos a Kafka (`orders.created.v1`). Un worker (`order-processor`) debe transformar cada evento en **un único resultado de negocio confiable**: aprobado o rechazado con importes correctos, persistido en MongoDB y comunicado a otros sistemas mediante `orders.processed.v1`.

El cálculo en sí es sencillo. La dificultad está en el entorno:

- Kafka entrega **al menos una vez**: habrá duplicados, reentregas tras rebalanceos y eventos fuera de orden.
- El enriquecimiento depende de **dos servicios externos** (Clients API en NestJS y Products API en Go) que pueden fallar, limitar tráfico o no responder.
- Persistir en MongoDB y publicar en Kafka son **dos sistemas sin transacción común**, por lo que cualquier caída entre ambos pasos puede dejar estados inconsistentes.

El objetivo de diseño es, por lo tanto: **un efecto de negocio por (orderId, eventVersion), nunca un resultado más nuevo sobrescrito por uno más antiguo, y ningún pedido persistido que no termine publicándose.**

---

## 2. Supuestos

Ambigüedades del enunciado resueltas explícitamente. Cada una es revisable en la defensa.

| # | Supuesto | Motivo |
|---|----------|--------|
| S1 | `eventVersion` es la **revisión del pedido** (monótona por `orderId`), no la versión del esquema. La versión del esquema la indica el sufijo del tópico (`.v1`). | El enunciado pide que "eventos obsoletos no sobrescriban un resultado más nuevo", lo que solo tiene sentido si la versión ordena revisiones de un mismo pedido. |
| S2 | Una revisión mayor de un pedido ya procesado **se procesa y reemplaza** el resultado anterior, y genera un nuevo evento de salida. "Un efecto de negocio" se interpreta como uno por par `(orderId, eventVersion)`. | Permite correcciones legítimas del productor sin violar la idempotencia. |
| S3 | Dos eventos con el mismo `orderId` y `eventVersion` pero distinto `eventId`: **gana el primero que se persiste**. El segundo se clasifica como `VERSION_CONFLICT`, no se aplica y se envía a la DLT para revisión. | Indica un error del productor. No existe un criterio de negocio para elegir, y descartar en silencio ocultaría el problema. |
| S4 | Una revisión menor que la persistida se clasifica como `STALE`: se descarta, se confirma el offset y se registra una métrica. No va a DLT. | Es un comportamiento esperado en un sistema con reentregas, no un error. |
| S5 | `unitPrice` proviene del evento y se considera el precio acordado. Products API no informa precios. | El contrato de Products API no incluye precio. |
| S6 | Un producto que no existe **en el mercado del pedido** responde 404 y el pedido queda `REJECTED` (`PRODUCT_NOT_FOUND`). | Products API recibe `market` como parámetro. |
| S7 | Un pedido `REJECTED` se persiste y publica **sin totales** (`totals: null`). Si el rechazo ocurre con toda la información disponible, los totales calculados se guardan solo en el documento persistido, como trazabilidad. | Un pedido rechazado no tiene importes comprometidos. Publicar totales podría inducir a error a los consumidores. |
| S8 | `TECHNICAL_FAILURE` se persiste y el mensaje original va a la DLT, pero **no** se publica en `orders.processed.v1`, porque el contrato lo define para aprobados y rechazados. Un pedido en `TECHNICAL_FAILURE` **puede reprocesarse** con el mismo evento (por ejemplo, un replay desde la DLT). | No es un estado final de negocio, sino un estado técnico recuperable. |
| S9 | Se evalúan todas las reglas de elegibilidad y se guardan **todas** las razones de rechazo en el documento. El evento publica en `reason` la primera según un orden determinista (primero cliente, luego productos en el orden del pedido). | Las razones completas facilitan el soporte. El contrato de salida mantiene un único `reason`. |
| S10 | Valores de enum desconocidos en las respuestas de las APIs (por ejemplo, un `status` nuevo) se tratan como **error externo definitivo** (`TECHNICAL_FAILURE`), no como rechazo de negocio. Los campos adicionales desconocidos se ignoran (*tolerant reader*). | Rechazar un pedido válido porque el proveedor amplió su contrato sería un efecto de negocio causado por un problema técnico. |
| S11 | Los servicios internos se comunican por una red privada sin autenticación entre servicios en esta versión. | Fuera del alcance de la prueba. Se registra como deuda. |
| S12 | Todas las fechas se manejan en UTC (ISO-8601). El orden se determina por `eventVersion`, nunca por `occurredAt`. | Evita depender de relojes de distintos productores. |

---

## 3. Responsabilidades y límites de los componentes

```
                ┌──────────────────────┐
 orders.created │                      │ GET /clients/{id}     ┌───────────────────┐
 ─────v1──────► │   order-processor    │ ────────────────────► │ clients-api (Nest)│
                │   (Java 21 / Spring) │                       └───────────────────┘
                │                      │ GET /products/{id}    ┌───────────────────┐
                │                      │ ────────────────────► │ products-api (Go) │
                │                      │                       └───────────────────┘
                │                      │ ──► MongoDB (orders + outbox embebido)
                │                      │ ──► orders.processed.v1
                │                      │ ──► orders.processing.dlt
                └──────────────────────┘
```

| Componente | Responsable de | No es responsable de | Datos que posee |
|---|---|---|---|
| **order-processor** | Validar el contrato de entrada, orquestar el enriquecimiento, aplicar reglas de elegibilidad, descuento e impuestos, garantizar idempotencia, persistir y publicar el resultado. | Conocer cómo se almacenan clientes o productos. Decidir el estado de clientes o productos. | Colección `orders`, contrato `orders.processed.v1`. |
| **products-api** | Exponer el catálogo de productos por mercado, con su estado y categoría fiscal. | Precios del pedido, impuestos, reglas de pedidos. | Catálogo de productos (semilla en memoria). |
| **clients-api** | Exponer clientes con estado, segmento, régimen fiscal y mercado. | Reglas de elegibilidad de pedidos. | Maestro de clientes (semilla en memoria). |
| **Kafka / MongoDB** | Infraestructura compartida. | — | — |

**Dependencias permitidas:** `order-processor` depende de ambas APIs. Las APIs no dependen entre sí ni del worker. Ninguna API conoce Kafka.

**Ubicación de las reglas fiscales:** las tasas de impuesto por mercado y categoría viven en `order-processor`. Products API solo informa la *categoría* fiscal del producto, que es un dato del catálogo. La *tasa* es una regla del proceso de pedidos. Así, cambiar una tasa no exige desplegar el catálogo.

---

## 4. Capas, puertos y adaptadores

### 4.1 order-processor

```
order-processor/src/main/java/.../
├── domain/                  # Java puro: sin Spring, Kafka, Mongo ni DTOs externos
│   ├── model/               # Order, OrderLine, Market, Currency, ClientSnapshot,
│   │                        # ProductSnapshot, OrderTotals, ProcessingOutcome, RejectionReason
│   ├── pricing/             # TaxRateTable, DiscountPolicy, LinePricer, MoneyRounding
│   ├── eligibility/         # EligibilityPolicy
│   └── validation/          # OrderValidator (reglas del contrato de entrada)
├── application/
│   ├── ProcessOrderUseCase  # orquestación: validar → enriquecer → evaluar → persistir
│   └── port/
│       ├── in/              # ProcessOrderCommand
│       └── out/             # ClientCatalog, ProductCatalog, OrderResultStore,
│                            # ProcessedEventPublisher, DeadLetterPublisher, Clock, IdGenerator
└── infrastructure/
    ├── kafka/in/            # listener, DTO de entrada, mapper DTO → comando
    ├── kafka/out/           # publicador de processed, publicador de DLT
    ├── http/                # ClientsApiClient, ProductsApiClient, DTOs, mappers, clasificación de errores
    ├── mongo/               # OrderDocument, mapper, OrderResultStore con escritura condicional
    ├── outbox/              # OutboxRelay (publicación diferida)
    └── config/              # timeouts, retry, beans, propiedades externalizadas
```

**Reglas:**

- `domain` no importa nada fuera del JDK. Esto se verifica con un test de **ArchUnit** en CI.
- Hay tres modelos para cada concepto: DTO externo (Kafka/HTTP), modelo de dominio y documento de persistencia. Los mapeos son explícitos, sin reflexión mágica.
- Los importes monetarios usan `BigDecimal` en todas las capas. La deserialización usa `USE_BIG_DECIMAL_FOR_FLOATS` para no pasar nunca por `double`.
- Los puertos existen solo donde hay un adaptador real que intercambiar o aislar en tests. No hay interfaces "por si acaso".

### 4.2 products-api (Go)

```
products-api/
├── cmd/products-api/main.go      # wiring, configuración, shutdown controlado
├── internal/product/             # Product, Service, Repository (interfaz), errores de dominio
├── internal/product/memory/      # repositorio semilla en memoria
├── internal/httpapi/             # handlers, contrato de error, middleware de logging
└── internal/faults/              # inyección de fallos (activada solo por variable de entorno)
```

- Usa `net/http` de la librería estándar. Desde Go 1.22 el ruteo soporta métodos y parámetros de ruta (`GET /products/{productId}`), así que un framework no aporta nada para un endpoint.
- `context.Context` se propaga desde el request hasta el repositorio, que respeta la cancelación.
- El handler traduce errores de dominio (`ErrNotFound`, `ErrInvalidMarket`) al contrato HTTP. El servicio no conoce códigos HTTP.

### 4.3 clients-api (NestJS)

```
clients-api/src/
├── clients/
│   ├── clients.controller.ts     # solo transporte
│   ├── clients.service.ts        # caso de uso
│   ├── clients.repository.ts     # interfaz (token de inyección)
│   ├── in-memory-clients.repository.ts
│   └── dto/                      # validación con class-validator
├── common/http-exception.filter.ts  # contrato de error compartido
├── faults/                       # inyección de fallos (por variable de entorno)
└── health/
```

- `strict: true` en `tsconfig`. El repositorio se inyecta por token, de modo que reemplazar la semilla por una base de datos no afecta al controller ni al service.

### 4.4 Contrato de error compartido (ambas APIs)

```json
{
  "error": {
    "code": "PRODUCT_NOT_FOUND",
    "message": "Product PRD-999 not found in market MX",
    "traceId": "3f2a…"
  }
}
```

Los códigos son estables y documentados. El consumidor decide según el **código HTTP** y el `code`, nunca según `message`.

---

## 5. Flujo principal y flujos de error

### 5.1 Flujo principal

1. **Consumo:** el listener recibe el mensaje (clave `orderId`). La deserialización usa `ErrorHandlingDeserializer`, así que un mensaje ilegible no bloquea la partición.
2. **Validación de contrato:** campos obligatorios, ítems no vacíos, `productId` único, `quantity` > 0 entero, `unitPrice` ≥ 0, mercado soportado y moneda coherente con el mercado. Un mensaje inválido va a la DLT (`VALIDATION`), se confirma su offset y no se persiste.
3. **Chequeo temprano (optimización):** se lee el documento del pedido. Si ya existe la misma revisión, se descarta como duplicado sin llamar a las APIs. **Esto no es la garantía de idempotencia**, solo ahorra llamadas. La garantía está en el paso 6.
4. **Enriquecimiento:** se consulta Clients API y, en paralelo con virtual threads, Products API para cada ítem. Cada llamada tiene su timeout y su política de retry.
5. **Evaluación de dominio:** elegibilidad y luego, si corresponde, cálculo por línea y totales. El resultado es `APPROVED` o `REJECTED`.
6. **Escritura condicional atómica** del resultado junto con el evento de salida embebido (`outbox.status = PENDING`). Ver §6.
7. **Commit del offset**, una vez que el resultado es durable.
8. **Publicación:** se intenta publicar en `orders.processed.v1` (camino rápido) y luego se marca `outbox.status = PUBLISHED`. Si falla, el `OutboxRelay` lo reintenta.

### 5.2 Clasificación de errores

| Situación | Categoría | ¿Se reintenta? | Estado persistido | ¿Publica processed? | ¿DLT? |
|---|---|---|---|---|---|
| JSON ilegible / no deserializable | `DESERIALIZATION` | No | — | No | Sí |
| Contrato de entrada inválido | `VALIDATION` | No | — | No | Sí |
| Cliente 404 | Rechazo de negocio (`CLIENT_NOT_FOUND`) | No | `REJECTED` | Sí | No |
| Cliente `BLOCKED` | `CLIENT_BLOCKED` | No | `REJECTED` | Sí | No |
| Mercado del cliente ≠ mercado del pedido | `CLIENT_MARKET_MISMATCH` | No | `REJECTED` | Sí | No |
| Producto 404 en el mercado | `PRODUCT_NOT_FOUND` | No | `REJECTED` | Sí | No |
| Producto `DISCONTINUED` | `PRODUCT_DISCONTINUED` | No | `REJECTED` | Sí | No |
| 429 / 500 / 502 / 503 / timeout | `EXTERNAL_TRANSIENT` | Sí, en proceso | `TECHNICAL_FAILURE` si se agotan los intentos | No | Sí, al agotarse |
| Otros 4xx, cuerpo inválido, enum desconocido | `EXTERNAL_PERMANENT` | No | `TECHNICAL_FAILURE` | No | Sí |
| MongoDB no disponible / error de escritura | `PERSISTENCE` | Sí, a nivel de mensaje | — | No | Sí, al agotarse |
| Error al publicar `orders.processed.v1` | `PUBLICATION` | Sí, con el relay | Sin cambio (`outbox PENDING`) | Pendiente | No |
| Misma revisión ya procesada | `DUPLICATE` | — | Sin cambio | No | No |
| Revisión menor a la persistida | `STALE` | — | Sin cambio | No | No |
| Misma revisión, distinto `eventId` | `VERSION_CONFLICT` | No | Sin cambio | No | Sí |

### 5.3 Política de timeouts y reintentos

| Parámetro | Valor inicial | Justificación |
|---|---|---|
| Connect timeout | 500 ms | Red interna. Si no conecta rápido, no va a conectar. |
| Read timeout por llamada | 2 s | Servicios de lectura simples. |
| Intentos máximos por llamada | 3 (1 + 2 retries) | Acota el tiempo de bloqueo de la partición. |
| Backoff | Exponencial, 200 ms → 800 ms, con jitter | Evita sincronizar reintentos entre instancias. |
| 429 | Respeta `Retry-After` con tope de 2 s; si excede el tope, se considera agotado | Evita amplificar una tormenta de límites. |
| Presupuesto total por mensaje | < 15 s, muy por debajo de `max.poll.interval.ms` | Evita rebalanceos provocados por reintentos. |
| Reintentos de persistencia (nivel mensaje) | `DefaultErrorHandler` con backoff exponencial, 6 intentos (~1 min), luego DLT | Un Mongo caído es sistémico: reintentar con pausa es mejor que vaciar la partición a la DLT de inmediato. |

**Nunca se reintentan:** errores de validación, 404, 400, enums desconocidos, `VERSION_CONFLICT` ni rechazos de negocio.

### 5.4 Respuestas parciales e interrupciones

- **Fallo parcial del enriquecimiento:** si un producto responde y otro agota sus reintentos, el pedido completo queda `TECHNICAL_FAILURE`. Nunca se persiste ni publica un cálculo con líneas incompletas.
- **Caída antes de la escritura (paso 6):** Kafka reentrega y se reprocesa desde cero. No hubo efectos.
- **Caída entre la escritura y el commit del offset:** Kafka reentrega, la escritura condicional detecta `DUPLICATE` y se confirma el offset. El evento pendiente lo publica el relay.
- **Caída entre la publicación y la marca `PUBLISHED`:** el relay vuelve a publicar con **el mismo `eventId` de salida**. Los consumidores deduplican por `eventId` (publicación al menos una vez, documentada en el contrato).

### 5.5 Metadata de la DLT (`orders.processing.dlt`)

Se conservan el valor original (bytes) y la clave originales. Se agregan estos headers:

| Header | Contenido |
|---|---|
| `x-order-id`, `x-event-id` | Si pudieron extraerse |
| `x-error-category` | Categoría de §5.2 |
| `x-error-cause` | Causa resumida, sanitizada y truncada a 500 caracteres. Sin stack traces ni datos del cliente. |
| `x-attempts` | Cantidad de intentos realizados |
| `x-failed-at` | Timestamp UTC |
| `x-component` | `order-processor` |
| `x-original-topic` / `-partition` / `-offset` | Para replay y auditoría |

---

## 6. Idempotencia y concurrencia

### 6.1 Modelo de concurrencia del worker

- **Listener tradicional (bloqueante) con virtual threads** (`spring.threads.virtual.enabled=true`). Las llamadas HTTP a productos se paralelizan con un `ExecutorService` de virtual threads y timeout global.
- La concurrencia del listener equivale al número de particiones asignadas. Dentro de una partición el procesamiento es **secuencial**, lo que preserva el orden por `orderId` cuando el productor usa esa clave.
- **No se confía en el orden de Kafka como garantía.** Rebalanceos, reentregas o un productor mal configurado pueden hacer que dos instancias procesen el mismo pedido a la vez. La corrección depende de la escritura condicional, no del particionado.

### 6.2 Mecanismo: escritura condicional con upsert

Documento con `_id = orderId`. La escritura del resultado es una única operación atómica:

```js
db.orders.updateOne(
  {
    _id: orderId,
    $or: [
      { eventVersion: { $lt: incomingVersion } },                  // revisión más nueva
      { eventVersion: incomingVersion, eventId: incomingEventId,
        status: "TECHNICAL_FAILURE" }                              // reintento de fallo técnico
    ]
  },
  { $set: { ...resultado, outbox: { eventId: nuevo, status: "PENDING", ... } },
    $setOnInsert: { firstReceivedAt: now } },
  { upsert: true }
)
```

- **Pedido nuevo:** no hay documento, el filtro no coincide y el upsert inserta.
- **Documento existente que cumple la condición:** se actualiza.
- **Documento existente que no la cumple:** el upsert intenta insertar con el mismo `_id` y MongoDB responde **duplicate key (E11000)**. Es el árbitro atómico. Solo una escritura concurrente puede ganar.

Ante un E11000 se relee el documento para **clasificar** el resultado, sin decidir nada más:

| Documento persistido vs. evento entrante | Clasificación | Acción |
|---|---|---|
| Misma versión, mismo `eventId` | `DUPLICATE` | Commit del offset, métrica `orders_duplicates_total` |
| Misma versión, distinto `eventId` | `VERSION_CONFLICT` | DLT, commit, alerta |
| Versión persistida mayor | `STALE` | Commit, métrica `orders_stale_total` |

### 6.3 Los tres casos del enunciado

| Caso | Resultado garantizado | Cómo se demuestra |
|---|---|---|
| Mismo `eventId` repetido, incluso en simultáneo | Un documento y un evento de salida | Test de integración: N hilos procesan el mismo evento contra Mongo real. Se verifica un documento y un `outbox.eventId`. |
| Distinto `eventId`, mismo `orderId` y `eventVersion` | Gana el primero que escribe y el otro va a la DLT como `VERSION_CONFLICT` | Test de integración concurrente con dos eventos. |
| Versión menor después de una mayor | Se descarta y el resultado mayor permanece intacto | Test: procesar v2, luego v1, y verificar que el documento sigue en v2. |

### 6.4 Política de offsets

- `enable.auto.commit=false` y `AckMode.RECORD` (commit manual por registro).
- El offset se confirma **solo** cuando el mensaje terminó en un estado durable: resultado escrito, enviado a la DLT o clasificado como duplicado o stale.
- La semántica resultante es **al menos una vez**, con efectos idempotentes.

---

## 7. Consistencia entre MongoDB y Kafka

### 7.1 Estrategia: outbox embebido en el documento del pedido

MongoDB garantiza atomicidad en la escritura de **un documento**. Por eso el evento de salida se guarda dentro del mismo documento del pedido:

```json
{
  "_id": "ORD-MX-000147",
  "schemaVersion": 1,
  "eventId": "01J8ZP6M5E4RH0K7Y2N9A3TQWX",
  "eventVersion": 1,
  "status": "APPROVED",
  "market": "MX",
  "currency": "MXN",
  "client": { "clientId": "CLI-99821", "segment": "WHOLESALE", "taxRegime": "GENERAL", "market": "MX" },
  "lines": [
    { "productId": "PRD-001", "sku": "BEB-600-PET", "taxCategory": "STANDARD",
      "quantity": 24, "unitPrice": "35.50", "grossSubtotal": "852.00",
      "discountRate": "0.03", "discount": "25.56", "netSubtotal": "826.44",
      "taxRate": "0.16", "taxAmount": "132.23", "lineTotal": "958.67" }
  ],
  "totals": { "grossSubtotal": "...", "discount": "...", "netSubtotal": "...", "tax": "...", "grandTotal": "..." },
  "rejectionReasons": [],
  "technicalError": null,
  "firstReceivedAt": "2026-09-18T15:42:10.500Z",
  "receivedAt": "2026-09-18T15:42:10.500Z",
  "processedAt": "2026-09-18T15:42:10.900Z",
  "trace": { "sourceTopic": "orders.created.v1", "partition": 3, "offset": 1842, "attempts": 1 },
  "outbox": {
    "eventId": "01J8ZP7…",
    "status": "PENDING",
    "createdAt": "2026-09-18T15:42:10.900Z",
    "publishedAt": null,
    "publishAttempts": 0,
    "payload": { "...evento orders.processed.v1..." }
  }
}
```

Los importes se guardan como `Decimal128` en MongoDB (en el ejemplo aparecen como texto por legibilidad).

### 7.2 Publicación

- **Camino rápido:** justo después de la escritura, el worker publica (productor Kafka con `enable.idempotence=true` y `acks=all`) y marca `PUBLISHED` con un update condicionado a `outbox.eventId` y `outbox.status = PENDING`.
- **Camino de recuperación:** un `OutboxRelay` programado cada 5 s busca documentos `PENDING` con antigüedad mayor a 10 s y los publica. Hay un índice parcial sobre `outbox.status = PENDING` para que la búsqueda sea barata.
- Con varias instancias, el relay puede publicar dos veces el mismo evento. Es aceptable porque el `eventId` de salida es estable. Si llega a ser un problema, se agrega un *lease* (`outbox.lockedUntil`) con `findOneAndUpdate`.

### 7.3 Cómo se evitan los estados inconsistentes del enunciado

| Estado inconsistente | Por qué no ocurre o cómo se recupera |
|---|---|
| Pedido persistido sin evento de salida | El evento se escribe en la **misma operación atómica** que el pedido. Si la publicación falla, queda `PENDING` y el relay lo publica. La métrica `outbox_pending_age_seconds` alerta si se acumula. |
| Evento publicado sin pedido persistido | Nunca se publica antes de que la escritura sea durable. El payload publicado se lee del documento persistido. |
| Más de un efecto de negocio por pedido | La escritura condicional admite un único resultado por `(orderId, eventVersion)`. Las republicaciones llevan el mismo `eventId` y el consumidor deduplica. |

**Trade-off asumido:** si llega una revisión nueva mientras el evento de la anterior sigue `PENDING`, el evento anterior se reemplaza y nunca se publica. Los consumidores reciben directamente el estado más reciente. Se considera correcto porque la revisión vieja quedó obsoleta, y queda documentado.

### 7.4 Índices

| Índice | Uso |
|---|---|
| `_id` (orderId), único implícito | Árbitro de concurrencia |
| `{ eventId: 1 }` | Soporte: buscar por evento de origen |
| `{ "outbox.status": 1, "outbox.createdAt": 1 }`, parcial con `status = PENDING` | Relay |
| `{ status: 1, processedAt: -1 }` | Operación: listar fallos técnicos recientes |

Los índices se crean al iniciar la aplicación con un inicializador idempotente, versionado junto al código.

---

## 8. Compatibilidad y evolución de contratos

### 8.1 Ownership

| Contrato | Dueño (productor) | Consumidores | Fuente de verdad |
|---|---|---|---|
| `orders.created.v1` | Sistema de captura de pedidos (externo, fuera del alcance) | order-processor | `contracts/events/orders.created.v1.schema.json` |
| `orders.processed.v1` | order-processor | Downstream (facturación, notificaciones, etc.) | `contracts/events/orders.processed.v1.schema.json` |
| `orders.processing.dlt` | order-processor | Operación / soporte | `contracts/events/dlt-headers.md` |
| `GET /clients/{id}` | Equipo de clients-api | order-processor | `contracts/http/clients-api.openapi.yaml` |
| `GET /products/{id}` | Equipo de products-api | order-processor | `contracts/http/products-api.openapi.yaml` |
| Contrato de error HTTP | Estándar compartido de plataforma | Todos | `contracts/http/error.schema.json` |

### 8.2 Reglas de versionado

- **Cambios compatibles (aditivos)** en la misma versión: nuevos campos opcionales. Los consumidores son *tolerant readers* e ignoran campos desconocidos.
- **Nuevos valores de enum:** se consideran **potencialmente incompatibles**. Se anuncian con antelación y el consumidor se actualiza antes que el productor (ver S10).
- **Cambios incompatibles en eventos:** se crea un tópico nuevo (`orders.processed.v2`) y el productor publica en ambos durante la migración (*expand → migrate → contract*). `v1` se retira cuando el último consumidor confirma la migración.
- **Cambios incompatibles en HTTP:** se crea una nueva ruta versionada (`/v2/products/...`) y la anterior se mantiene hasta que no tenga tráfico, verificado con métricas por ruta.

### 8.3 Validación en CI

- Los fixtures de ejemplo de cada evento se validan contra su JSON Schema.
- El productor valida en sus tests que lo que serializa cumple el schema. Por ejemplo, el test del publicador de order-processor valida contra `orders.processed.v1.schema.json`.
- Cada API valida en sus tests de endpoint que sus respuestas cumplen su OpenAPI.
- Un job verifica la **compatibilidad hacia atrás** de los schemas modificados respecto de `main`. Un cambio incompatible en un archivo `v1` hace fallar el PR.
- *Contract tests* dirigidos por el consumidor (por ejemplo, Pact) quedan como evolución opcional.

### 8.4 Coordinación entre equipos

Un cambio de contrato se propone como PR sobre `contracts/`, con revisión obligatoria del dueño y de al menos un consumidor (CODEOWNERS). El orden de despliegue es: consumidor tolerante primero, productor después.

### 8.5 Qué no debe filtrarse

- Products API no expone costos, proveedores ni stock. order-processor no necesita esos datos.
- Clients API no expone datos de contacto, condiciones crediticias ni datos tributarios identificatorios. Solo los atributos que afectan las reglas.
- order-processor persiste un **snapshot mínimo** del cliente y los productos (los campos que justifican el cálculo) y no replica el maestro completo.
- El evento de salida no publica datos del cliente más allá de lo que exige el contrato.

---

## 9. Riesgos y posibles regresiones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Pérdida de precisión al deserializar importes como `double` | Totales incorrectos | `USE_BIG_DECIMAL_FOR_FLOATS`, `Decimal128` en Mongo, tests de redondeo con casos límite (x.xx5) |
| Reintentos largos que bloquean la partición y provocan rebalanceos | Lag y reprocesamiento masivo | Presupuesto de tiempo por mensaje muy inferior a `max.poll.interval.ms` |
| Tormenta de 429 amplificada por reintentos | Degradación de las APIs | Jitter, `Retry-After` con tope y métrica de retries. Caché de productos como siguiente paso. |
| Mal manejo del E11000 en el driver (confundirlo con un error de persistencia) | Duplicados enviados a la DLT | Mapeo explícito en el adaptador y test de integración dedicado |
| Evento `PENDING` reemplazado por una revisión nueva | Un consumidor no ve una revisión intermedia | Decisión documentada (§7.3) |
| Productor que emite la misma versión con distinto `eventId` | Pedidos en DLT | Alerta sobre `VERSION_CONFLICT` y runbook de replay |
| Cambio de tasas fiscales | Regresión silenciosa en importes | Tabla de tasas aislada y tests parametrizados por mercado y categoría |
| Un enum nuevo agregado por un proveedor | Pedidos en `TECHNICAL_FAILURE` | Política S10, alerta y replay desde la DLT tras actualizar el consumidor |
| Hot partition (un distribuidor con mucho volumen) | Latencia | Métrica de lag por partición. Revisar la estrategia de clave si aparece. |

---

## 10. Estrategia de pruebas

| Nivel | order-processor (Java) | products-api (Go) | clients-api (NestJS) |
|---|---|---|---|
| **Unitarias de dominio** | Impuestos por mercado y categoría (parametrizados), exención por régimen, descuento mayorista (19/20/21 unidades), redondeo HALF_UP y agregación de líneas redondeadas, elegibilidad (cada razón), validaciones y límites | Servicio con repositorio fake (table-driven) | Service con repositorio mock (Jest) |
| **Aplicación** | `ProcessOrderUseCase` con fakes de los puertos: rechazo, aprobación, fallo transitorio agotado, fallo definitivo | — | — |
| **Transporte** | Adaptadores HTTP contra WireMock: clasificación de 404, 429, 5xx, timeout y cuerpo inválido; retries y backoff | Handler con `httptest`: 200, 400, 404, contrato de error, cancelación de contexto | Endpoint con Supertest: 200, 400, 404, contrato de error |
| **Integración** | Testcontainers (Kafka y MongoDB): flujo completo, duplicado concurrente, `VERSION_CONFLICT`, `STALE`, envío a DLT con headers, relay del outbox tras un fallo de publicación | — | — |
| **Arquitectura** | ArchUnit: `domain` no depende de Spring, Kafka, Mongo ni `infrastructure` | — | — |
| **Contratos** | Payload de salida validado contra JSON Schema | Respuestas validadas contra OpenAPI | Respuestas validadas contra OpenAPI |
| **Smoke E2E** | Script que, con `docker compose up`, publica eventos de ejemplo y verifica Mongo y `orders.processed.v1` | | |

**Simulación de fallos:** las APIs incluyen un middleware de inyección de fallos, **desactivado por defecto** (`FAULT_INJECTION_ENABLED=true` solo en compose y tests). Responde de forma determinista según IDs semilla documentados, por ejemplo `PRD-FAIL-503`, `PRD-SLOW` (timeout), `CLI-FAIL-429` y `CLI-BAD-ENUM`. El middleware vive fuera del dominio y no contamina el servicio.

---

## 11. Decisiones descartadas

| Alternativa | Motivo del descarte |
|---|---|
| Spring WebFlux / programación reactiva | No hay un problema de throughput que lo justifique. Virtual threads ofrecen concurrencia suficiente con código lineal, más fácil de depurar y revisar. |
| Retry topics no bloqueantes (`@RetryableTopic`) | Rompen el orden por `orderId`: una revisión vieja reintentada podría llegar después de una nueva. Además agregan tópicos y complejidad operativa. |
| Transacciones Kafka combinadas con MongoDB | No existe una transacción distribuida real entre ambos. Daría una falsa sensación de garantía. |
| Colección de outbox separada con transacción multi-documento | Funciona, pero requiere réplica set y transacciones. El outbox embebido logra lo mismo con atomicidad de un solo documento. |
| CDC (Change Streams / Debezium) para publicar | Más infraestructura y más componentes que operar. Es una evolución razonable si el relay no escala. |
| Lock distribuido en Redis | Un lock no garantiza nada si expira a mitad del proceso. La restricción en la base es más simple y verificable. |
| `exists` seguido de `save` | No es atómico bajo concurrencia (explícitamente descartado por el enunciado). Se usa solo como optimización (§5.1, paso 3). |
| Framework web en Go (Gin, Echo) | La librería estándar de Go 1.22 cubre el ruteo necesario. Menos dependencias. |
| Avro con Schema Registry | Más robusto a escala, pero JSON Schema en el repositorio es suficiente para esta versión. Queda como evolución opcional. |
| Base de datos propia para las APIs | No se exige. Los repositorios están detrás de una interfaz y el reemplazo no afecta a los consumidores. |
| App Flutter | Opcional. Se prioriza la solidez del núcleo. |

---

## 12. Plan de implementación incremental

Cada paso termina con pruebas en verde y un commit (o grupo de commits) que se entiende por sí solo.

| # | Entrega | Criterio de terminado |
|---|---|---|
| 0 | Esta propuesta, la estructura del repositorio y los contratos (`contracts/`) | Schemas y OpenAPI revisados y fixtures válidos |
| 1 | Dominio de order-processor: validación, elegibilidad, descuento, impuestos y redondeo | Tests unitarios completos y test de ArchUnit |
| 2 | products-api: endpoint, semilla (≥ 10 productos), contrato de error, health, shutdown, inyección de fallos | `go test ./...` y `go vet` en verde |
| 3 | clients-api: endpoint, semilla (≥ 6 clientes), validación, filtro de errores, health, inyección de fallos | `npm test` y e2e en verde |
| 4 | `docker-compose.yml` con Kafka, MongoDB y las dos APIs | Healthchecks en verde |
| 5 | Caso de uso y adaptadores HTTP con timeouts, retries y clasificación de errores | Tests con WireMock |
| 6 | Adaptador Mongo con escritura condicional e índices | Tests de concurrencia, duplicado, stale y conflicto con Testcontainers |
| 7 | Listener Kafka, política de offsets, DLT con metadata y publicación con relay de outbox | Test de integración del flujo completo y de recuperación del outbox |
| 8 | Observabilidad: logs JSON con `orderId`/`eventId` y métricas Micrometer | Métricas visibles en `/actuator/prometheus` |
| 9 | Documentación: README, implementation notes, technical leadership, ADRs y uso de IA | Un tercero puede levantar y probar el sistema siguiendo el README |

**Si el tiempo no alcanza**, se recorta en este orden: métricas reales (se deja la propuesta), luego el relay programado (se mantiene el camino rápido y se documenta el riesgo), luego el smoke E2E. **No se recortan** el dominio, la idempotencia ni sus pruebas.

---

## Anexo: observabilidad (resumen)

- **Logs JSON** con `orderId`, `eventId`, `eventVersion`, `partition`, `offset` y `outcome` en el MDC. Se registra una línea por transición: `received`, `validated`, `enriched`, `evaluated`, `persisted`, `published`, `dlt`, `duplicate`, `stale`.
- **Métricas:** `orders_processed_total{status,market}`, `orders_rejected_total{reason}`, `http_client_retries_total{target}`, `orders_dlt_total{category}`, `orders_duplicates_total`, `orders_stale_total`, `orders_processing_duration_seconds` (histograma), `outbox_pending_age_seconds` y el lag del consumidor.
- **Pedido "perdido"** (el cliente dice que lo envió y no aparece aprobado ni rechazado), se investiga en este orden:
  1. Buscar en Mongo por `orderId`. Puede estar en `TECHNICAL_FAILURE` o con el outbox `PENDING`.
  2. Buscar en los logs por `orderId`. ¿Hay un `received`? ¿Hasta qué transición llegó?
  3. Buscar en la DLT por el header `x-order-id`, en particular por `VALIDATION` o `VERSION_CONFLICT`.
  4. Si no hay rastro, revisar el lag del consumidor y confirmar con el productor que el evento se publicó, en qué tópico y con qué clave.
  5. Si nunca llegó a `orders.created.v1`, el problema está aguas arriba.
