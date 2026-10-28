# Notas de implementación

Diferencias reales respecto de `docs/architecture-proposal.md` y decisiones
que la propuesta no fijaba. La propuesta no se reescribe: se anota aquí.

## Resumen frente a la propuesta

Cómo quedó cada sección de la propuesta. "Igual" significa que se implementó como estaba
escrita; las notas de las tablas por hito (más abajo) explican cada diferencia.

| Propuesta | Estado | Diferencias y detalles |
|---|---|---|
| §2 Supuestos S1–S12 | Igual | S11 (sin autenticación entre servicios) queda como deuda. |
| §3 Responsabilidades y límites | Igual | Las tasas fiscales viven en order-processor; las APIs no conocen Kafka. |
| §4.1 Capas de order-processor | Con diferencias | Sin puerto `DeadLetterPublisher` ni `Clock` propio (N2, K1). Sin POJO `OrderDocument`: el layout BSON lo arma el mapper (M1). Paquete extra `domain/idempotency` (N13). |
| §4.2 products-api | Igual | Detalles del contrato y de los fallos en P1–P7. |
| §4.3 clients-api | Con diferencias | Inyección de fallos como interceptor, no middleware (C4). NestJS 11 (C5). |
| §4.4 Contrato de error | Igual | Origen del `traceId` en P7 / C7. |
| §5.1 Flujo principal | Con diferencias | El offset se confirma **después** de la publicación inmediata, no antes (K16). |
| §5.2 Clasificación de errores | Con diferencias | Todo 5xx es transitorio (N1). Un 404 sin código de negocio es permanente (N6). Categoría extra `UNEXPECTED` (K3). Números con tipo incorrecto son `DESERIALIZATION` (K6). |
| §5.3 Timeouts y reintentos | Igual en valores | `Retry-After` también en 5xx (N7), *equal jitter* (N8), presupuesto de enriquecimiento de 12 s (N12), `serverSelectionTimeoutMS=5000` (M7). |
| §5.4 Respuestas parciales | Igual | — |
| §5.5 Metadata de la DLT | Igual | Definición de `x-attempts` (K4) y formato de headers (K5). |
| §6 Idempotencia y concurrencia | Igual | Reintento del upsert si la relectura tras un E11000 dice "escribible" (M2). Ver [ADR 0001](adr/0001-idempotencia-con-escritura-condicional.md) y, para el modelo de concurrencia, [ADR 0003](adr/0003-modelo-de-concurrencia-del-worker.md). |
| §7 Consistencia Mongo–Kafka | Igual | Un `TECHNICAL_FAILURE` se guarda sin outbox y borra el anterior (N14, M3). Relay sin lease (K11). Ver [ADR 0002](adr/0002-outbox-embebido-en-el-documento-del-pedido.md). |
| §7.4 Índices | Igual | Se crean al construir el store, con nombres fijos (M8). |
| §8.1 Ownership | Incompleto | `contracts/events/dlt-headers.md` no existe (G1). |
| §8.3 Validación en CI | Parcial | Sin CI ni job de compatibilidad de schemas (G2). La validación contra OpenAPI existe en clients-api (C8), no en products-api (P8). |
| §9 Riesgos | Igual | La caché de productos sigue siendo el siguiente paso, no está implementada. |
| §10 Estrategia de pruebas | Parcial | Todo lo de la tabla salvo el script de smoke E2E (G3) y la validación contra OpenAPI en Go (P8). |
| §11 Decisiones descartadas | Respetadas | Además se descartaron Spring Data MongoDB (M1) y resilience4j / `RestClient` (N4). |
| §12 Plan incremental | Igual | De los recortes previstos solo se aplicó el del smoke E2E (G3). Las métricas y el relay están implementados. |
| Anexo: observabilidad | Igual | Transición extra `publication_pending` (O2). Gauge del outbox actualizado por el relay (O7). |

## products-api (hito 2)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| P1 | Códigos HTTP del contrato | El OpenAPI de `GET /products/{productId}` declaraba 200/404/429/500 | Se agregaron **400** (`INVALID_MARKET`) y **503** (`SERVICE_UNAVAILABLE`) a `contracts/http/products-api.openapi.yaml` | La validación de `market` (§4.2 `ErrInvalidMarket`, §10 test de 400) y el fallo semilla `PRD-FAIL-503` no tenían respuesta declarada. Cambio aditivo y compatible. Aprobado el 2026-10-25. |
| P2 | IDs de fallo | Ejemplos: `PRD-FAIL-503`, `PRD-SLOW` | Además `PRD-FAIL-500`, `PRD-FAIL-429` (con `Retry-After: 1`) y `PRD-BAD-ENUM` (200 con `status: "ARCHIVED"`) | Cubren las filas de §5.2 que order-processor debe clasificar en el hito 5: 429 y 5xx transitorios, enum desconocido como `EXTERNAL_PERMANENT` (S10). |
| P3 | `PRD-SLOW` | "timeout" | Espera 5 s (más que el read timeout de 2 s de §5.3) y luego sigue al handler real, que responde 404 porque el ID no está en la semilla. Si el cliente corta antes, la espera termina. | Un fallo inyectado no debe tener datos de catálogo propios. Con el timeout de §5.3 el cliente nunca ve el 404. |
| P4 | Request cancelado o vencido | No especificado | El handler responde 503 `SERVICE_UNAVAILABLE`. En el access log, si no se escribió respuesta y el contexto terminó, se registra `status: 499`. | Si el cliente se fue, la respuesta se descarta. Si fue un deadline del servidor, 503 se clasifica como transitorio. El 499 evita que un timeout aparezca como 200 en los logs. |
| P5 | `market` en minúsculas | No especificado | `mx` se rechaza con 400 | El contrato define el enum en mayúsculas. Normalizar ocultaría un error del llamador. |
| P6 | Rutas desconocidas | No especificado | 404 `ROUTE_NOT_FOUND` con el contrato de error, también para un método equivocado (no 405) | Toda respuesta de error respeta `{"error":{...}}`. Con el catch-all `/` de `net/http`, el mux no distingue 404 de 405, y no aporta valor a un consumidor interno. |
| P7 | `traceId` | No especificado de dónde sale | Se toma del header `X-Trace-Id` si viene. Si no, se genera (16 bytes aleatorios en hex) y se devuelve en el mismo header. | Permite que order-processor propague su traza más adelante sin cambiar la API. |
| P8 | Validación contra OpenAPI (§10) | "Respuestas validadas contra OpenAPI" | Los tests decodifican las respuestas con structs estrictos (`DisallowUnknownFields`). **No** hay validación automática contra el YAML. | Validar el YAML desde Go requiere una dependencia externa y `products-api` usa solo la librería estándar. Los fixtures de `contracts/` cubren la forma. Queda como deuda. |

## clients-api (hito 3)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| C1 | Códigos HTTP del contrato | El OpenAPI de `GET /clients/{clientId}` declaraba 200/404/429/500 | Se agregó **400** (`INVALID_REQUEST`) y `pattern: ^[A-Za-z0-9-]{1,64}$` al path param en `contracts/http/clients-api.openapi.yaml`. Los códigos de cada respuesta quedan en su descripción. | §10 pide un test de 400 y el `ValidationPipe` necesita una regla que validar. Cambio aditivo, igual que P1. Aprobado el 2026-10-25. |
| C2 | IDs de fallo | Ejemplos: `CLI-FAIL-429`, `CLI-BAD-ENUM` | `CLI-FAIL-429` (con `Retry-After: 1`), `CLI-FAIL-500`, `CLI-SLOW` y `CLI-BAD-ENUM` (200 con `status: "SUSPENDED"`). **Sin** `CLI-FAIL-503`. | Todos usan códigos ya declarados en el contrato. Un 503 habría obligado a ampliar el OpenAPI sin necesidad: 500 y 429 ya cubren el caso transitorio. Decidido el 2026-10-25. |
| C3 | `CLI-SLOW` | "timeout" | Espera 5 s y sigue al handler real, que responde 404 (no está en la semilla). Si el cliente corta antes, `race` entre el timer y el evento `close` de la respuesta cancela la espera: el handler no se ejecuta, el filtro no escribe nada (`ClientClosedRequestError`) y el access log registra 499. | Igual que P3: un request abandonado no debe seguir ocupando recursos ni consultar el repositorio. |
| C4 | Ubicación de la inyección de fallos | "Middleware" | `NestInterceptor` global en `src/faults/`, registrado solo si `FAULT_INJECTION_ENABLED=true`. | Un middleware de Nest corre antes del ruteo y no tiene `req.params`. El interceptor corre después, lee `clientId` ya resuelto y sigue fuera del service y del repositorio. |
| C5 | Versión de NestJS | No especificado | NestJS **11**, no 12. Build con `tsc`, sin `@nestjs/cli`. | Nest 12 es ESM-only y su tooling pasó a Vitest. La propuesta (§10) fija Jest, cuyo soporte ESM sigue siendo experimental. Nest 11 sigue recibiendo releases. |
| C6 | Traducción de errores | "El handler traduce errores de dominio" (Go) | El service lanza `ClientNotFoundError` (sin HTTP) y el filtro global lo traduce a 404 `CLIENT_NOT_FOUND`. Ruta o método inexistente → 404 `ROUTE_NOT_FOUND` (como P6). Otros 4xx del framework → `INVALID_REQUEST`. Cualquier otro error → 500 `INTERNAL_ERROR` con mensaje genérico; el detalle va al log con el `traceId`. | El controller queda solo como transporte y ninguna respuesta de error sale del contrato `{"error":{...}}`. |
| C7 | `traceId` | No especificado | Igual que P7: header `X-Trace-Id` si viene; si no, 16 bytes aleatorios en hex. | Misma convención en ambas APIs. |
| C8 | Validación contra OpenAPI (§10) | "Respuestas validadas contra OpenAPI" | Los e2e validan cada respuesta con Ajv contra `components.schemas.Client`, la respuesta de `/health` del YAML y `error.schema.json`. Además comparan las respuestas 200 con los fixtures de `contracts/fixtures/http/clients/valid`. | En Node la dependencia es solo de desarrollo y cierra la deuda que P8 deja abierta en Go. |
| C9 | Logs | No especificado para las APIs | `ConsoleLogger({ json: true })` de Nest: una línea JSON por evento. Access log por request con los mismos campos que products-api (`method`, `path`, `query`, `status`, `durationMs`, `traceId`), con 499 si el cliente cerró antes de la respuesta (como P4). Los errores inesperados llevan `traceId` y `stack` en campos separados. | Mismo formato que products-api para agregar logs de ambas APIs con la misma herramienta, sin agregar dependencias. Los campos van dentro de `message`, que es la forma que usa el logger de Nest. |
| C10 | `npm audit` | — | `overrides: { "multer": "^2.5.0" }` en `package.json`. `npm audit`: 0 vulnerabilidades. | `@nestjs/platform-express@11.2.8` fija `multer@2.4.0` (GHSA-q7qp-rjg7-q26m, afecta `<2.5.0`). La API no monta multer, pero el override es un cambio de versión menor y deja el audit limpio. Se quita cuando Nest actualice la dependencia. |

## Infraestructura local (hito 4)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| I1 | Particiones | No especificado | 3 particiones por tópico, factor de replicación 1. `orders.processing.dlt` tiene las mismas particiones que `orders.created.v1`. | 3 particiones permiten ver concurrencia entre particiones (§6.1) con un solo broker. El `DeadLetterPublishingRecoverer` de Spring publica por defecto en la misma partición que el mensaje original, por lo que el DLT necesita al menos las mismas particiones que el origen. |
| I2 | Creación de tópicos | No especificado | Servicio `kafka-init` que termina después de crear los tópicos (`--if-not-exists`, idempotente). Broker con `auto.create.topics.enable=false`. | Un nombre de tópico mal escrito falla (`UNKNOWN_TOPIC_OR_PARTITION`) en vez de crear un tópico vacío en silencio. |
| I3 | Listeners de Kafka | No especificado | `kafka:9092` para la red de compose y `localhost:9094` para el host. | Los contenedores y los procesos que corren en el host (tests, smoke, order-processor fuera de Docker) necesitan direcciones anunciadas distintas. |
| I4 | MongoDB | No especificado | `mongo:8.0` standalone y sin autenticación, solo para desarrollo local. | La escritura condicional (§6.2) es atómica en un solo documento y no requiere replica set ni transacciones. Sin credenciales no hay secretos en el repo (regla 8). |
| I5 | Imagen final de products-api | No especificado | `alpine` con usuario sin privilegios, no `distroless`/`scratch`. | El healthcheck usa el `wget` de busybox. Con distroless habría que agregar al binario un subcomando de healthcheck que solo existiría para eso. |
| I6 | Arranque de clients-api | `npm start` | La imagen ejecuta `node dist/main.js` directamente. | `npm` no reenvía SIGTERM al proceso hijo, y `enableShutdownHooks` no llegaría a cerrar el servidor. |
| I7 | order-processor en compose | La propuesta lo incluye en el stack | Se agrega en el hito 7, cuando exista el listener. | Este hito cubre la infraestructura y las APIs. |
| I8 | Versiones de imágenes | No especificado | Tags exactos: `apache/kafka:4.3.2`, `mongo:8.0.34`, `golang:1.26.10-alpine3.24`, `node:22.23.4-alpine3.24`, `alpine:3.24.3`. | Builds reproducibles. |

## Caso de uso y adaptadores HTTP de order-processor (hito 5)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| N1 | 5xx no listados | §5.2 lista 429 / 500 / 502 / 503 / timeout como transitorios y no dice nada de 504 u otros 5xx | **Todo 5xx** se trata como transitorio y se reintenta. Errores de red (`IOException`) también. | Un 504 (gateway timeout) es el mismo caso que un timeout. Tratar 501 o 505 como permanentes exigiría una lista que nadie va a mantener. Confirmado el 2026-10-28. |
| N2 | Puertos de salida | §4.1 lista `DeadLetterPublisher` y `Clock` entre los puertos | `DeadLetterPublisher` **no existe todavía**. `Clock` es el `java.time.Clock` del JDK, sin puerto propio. | La DLT necesita los bytes y headers originales del registro (§5.5), que el caso de uso no ve: la publica el listener en el hito 7. El caso de uso devuelve `ProcessOrderResult.Failed` y el listener decide. `Clock` ya es una abstracción inyectable del JDK. |
| N3 | `IdGenerator` | Puerto de salida | El puerto existe y los tests usan una implementación secuencial. **No hay adaptador real todavía.** | Se necesita para el `eventId` del outbox. Se escribe junto al adaptador Mongo (hito 6), que es quien lo consume. |
| N4 | Cliente HTTP | No especificado | `java.net.http.HttpClient` del JDK + Jackson, sin `RestClient` ni resilience4j. El reintento está en `ResilientHttpClient`, unas 130 líneas. | El JDK ya da connect timeout, timeout por request y es compatible con virtual threads. Una librería de resiliencia agregaría una dependencia para una política de tres parámetros. |
| N5 | Alcance del read timeout | "2 s por llamada" | `HttpRequest.timeout` cubre hasta recibir los **headers**, no la lectura del cuerpo. | Limitación del JDK. Los cuerpos son JSON de unos cientos de bytes y el presupuesto total del caso de uso acota el peor caso. |
| N6 | Qué es un 404 de negocio | "Cliente 404" / "Producto 404" → rechazo | Solo es rechazo de negocio un 404 con `error.code` = `CLIENT_NOT_FOUND` / `PRODUCT_NOT_FOUND`. Otro 404 (p. ej. `ROUTE_NOT_FOUND` por una URL base mal configurada) es `EXTERNAL_PERMANENT`. | Sin esta distinción, una mala configuración rechazaría en silencio todos los pedidos como "cliente inexistente" y los publicaría. |
| N7 | `Retry-After` | "429 respeta `Retry-After` con tope de 2 s" | Se respeta en 429 **y en 5xx**. Solo segundos enteros (lo que declaran los OpenAPI); una fecha HTTP o un valor inválido se ignora y se usa el backoff. Si supera el tope, no se espera: se corta con los intentos hechos hasta ahí. | RFC 9110 lo permite en 503. Ignorar un valor ilegible es más seguro que fallar por él. |
| N8 | Backoff | "Exponencial, 200 ms → 800 ms, con jitter" | Multiplicador 4 (200 ms, 800 ms) y *equal jitter*: la espera cae en [base/2, base]. | Full jitter puede dar esperas casi nulas y no desincroniza mejor con solo dos reintentos. |
| N9 | Respuesta inconsistente | "cuerpo inválido, enum desconocido" → permanente | Además es permanente: campo obligatorio ausente, o `clientId` / `productId` distinto del pedido. El mensaje nombra el campo pero **nunca incluye el valor recibido**. | El valor viene de un tercero y acaba en Mongo y en la DLT (§5.5 pide causas sanitizadas). |
| N10 | Id en la ruta | No especificado | El id del evento se codifica como segmento de ruta (`/` → `%2F`). | Un `productId` de `../admin` en el evento no debe cambiar la ruta pedida. |
| N11 | Paralelismo | "Cliente y, en paralelo, productos" | Cliente **y** productos van en paralelo y se esperan todos, aunque el cliente no exista. Se espera en orden fijo (cliente, luego ítems); si hay varios fallos gana el primero en ese orden. | La elegibilidad junta todas las razones de rechazo (S9), así que los productos hacen falta aunque falte el cliente. El orden fijo hace determinista la categoría persistida. |
| N12 | Presupuesto total | "< 15 s por mensaje" | `ProcessOrderUseCase` recibe `enrichmentTimeout` y lo aplica a todo el enriquecimiento; al agotarse cancela las llamadas y persiste `EXTERNAL_TRANSIENT`. El valor concreto se fija al cablear el caso de uso en el hito 7. | Los timeouts por llamada ya acotan el peor caso a ≈ 7 s (3 × 2 s + esperas), medido contra `CLI-SLOW` / `PRD-SLOW`. Esto es la red de seguridad. |
| N13 | Clasificación de revisiones | §6.2 la describe como tabla | Es `domain/idempotency/RevisionClassifier`, función pura. El caso de uso la usa para el chequeo temprano (§5.1 paso 3) y el adaptador Mongo la reutilizará para clasificar tras un E11000. | Evita escribir la misma tabla dos veces. La garantía de idempotencia sigue siendo la escritura condicional. |
| N14 | Fallo técnico en el outbox | §5.2: `TECHNICAL_FAILURE` no publica | Un `TECHNICAL_FAILURE` se persiste **sin outbox** (`ProcessedOrder.outboxEventId` vacío). | `orders.processed.v1` solo admite `APPROVED` / `REJECTED` (S8). Un outbox `PENDING` sin evento publicable lo recogería el relay y fallaría. |
| N15 | Logs del caso de uso | "Una línea por transición" | Se emiten por SLF4J sin ids en el texto. `orderId`, `eventId` y `eventVersion` los pone el MDC, que se agrega en el hito 8. | El MDC lo establece quien conoce el registro (el listener). |
| N16 | Toolchain local | JDK ≥ 21 | En la máquina de desarrollo el sistema solo tenía un JRE 25; los comandos Maven se ejecutaron con `JAVA_HOME` apuntando a un JDK 21. | Sin `javac`, `./mvnw` falla con `release version 21 not supported`. Un `mvn test` sobre `target/` ya compilado puede pasar sin avisar. |

## Adaptador Mongo de order-processor (hito 6)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| M1 | Acceso a Mongo | `mongo/`: `OrderDocument`, mapper, store | Driver sync (`mongodb-driver-sync`) con `MongoCollection<Document>`, **sin Spring Data**. El "documento Mongo" es el layout BSON que arma `OrderDocumentMapper` de forma explícita; no hay POJO intermedio. | Spring Data guarda `BigDecimal` como `String` por defecto y oculta el `updateOne` con filtro `$or` + upsert, que es justo lo que hay que poder leer y defender. El `MongoClient` lo sigue creando el autoconfig de Boot (`spring.data.mongodb.uri`). |
| M2 | Carrera tras un E11000 | "Se relee el documento para clasificar, sin decidir nada más" | Si al releer la clasificación dice que el evento **sí** era escribible (p. ej. otra instancia insertó un `TECHNICAL_FAILURE` del mismo evento entre ambas operaciones), se reintenta el upsert. Tope de 3 intentos; después, `OrderStorageException`. | Devolver `DUPLICATE` en ese caso perdería un resultado válido. El filtro condicional sigue siendo el árbitro. **Este camino no tiene test**: forzar esa intercalación de forma determinista exige instrumentar el driver. |
| M3 | Outbox en `TECHNICAL_FAILURE` | §7.1 no lo cubre | El update hace `$unset: {outbox}`. Una revisión nueva que falla técnicamente elimina el outbox de la anterior, aunque siga `PENDING`. | Es el trade-off de §7.3 (una revisión nueva reemplaza al evento pendiente) aplicado al caso sin evento publicable. Si quedara el outbox viejo, el relay publicaría un resultado que ya no es el del documento. |
| M4 | `outbox.payload` | "evento orders.processed.v1" | Se guarda con los campos del JSON Schema. Importes como `Decimal128` y `occurredAt` como BSON Date (= `processedAt`). La conversión a JSON (número y fecha ISO) queda para el publicador del hito 7. | §7.3: el payload publicado se lee del documento persistido. |
| M5 | Campos extra del documento | Ejemplo de §7.1 | `clientId` en la raíz y `client.status`. `client` es `null` cuando el cliente no existe; `technicalError` es `{category, cause, attempts}`. | Sin `clientId` en la raíz, un `CLIENT_NOT_FOUND` no dejaría rastro de qué cliente se pidió. `client.status` explica un `CLIENT_BLOCKED`. |
| M6 | Write concern | No especificado | `WriteConcern.MAJORITY` explícito en la colección. | "Durable" es la condición para confirmar el offset (§6.4). No se depende del default del servidor. |
| M7 | Mongo caído | `PERSISTENCE`, reintento a nivel de mensaje | `serverSelectionTimeoutMS=5000` en la URI (el default del driver es 30 s). Cualquier `MongoException` que no sea E11000 → `OrderStorageException`. | 6 reintentos × 30 s bloquearían la partición varios minutos y superarían `max.poll.interval.ms`. |
| M8 | Creación de índices | "Inicializador idempotente al iniciar" | `OrderIndexes.ensure` corre al construir el bean del store, con nombres fijos. Si Mongo no está disponible, la aplicación no arranca. | Lo que depende del store (el listener del hito 7) no puede empezar antes. Con nombres fijos, un cambio de opciones falla al arrancar en vez de crear un índice distinto en silencio. |
| M9 | Tests que requieren Docker | No especificado | Los tests con Testcontainers se llaman `*IT` y los corre `maven-failsafe-plugin` en `./mvnw verify`. `./mvnw test` sigue sin requerir Docker. El test de contexto pasó a `OrderProcessorApplicationIT`. | El arranque ahora crea índices y necesita un Mongo real. Un solo contenedor `mongo:8.0.34` (la misma imagen que compose) se comparte entre todos los IT. |

## Listener, DLT, publicación y relay de order-processor (hito 7)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| K1 | Envío a la DLT | §5.2 / §5.5 | **Un solo camino**: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. Cuando el caso de uso devuelve `Failed` (VALIDATION, VERSION_CONFLICT, EXTERNAL_*), el listener lanza `DeadLetterException`, marcada como no reintentable. La deserialización tampoco se reintenta (default de Spring). Todo lo demás se reintenta 6 veces con backoff (§5.3) y luego va a la DLT. | Mismos headers y misma garantía para todas las categorías: si el envío a la DLT falla, el offset no se confirma y el registro se vuelve a procesar. Por eso no hay puerto `DeadLetterPublisher` en `application/` (nota N2). |
| K2 | Bytes originales en la DLT | "Se conservan el valor original (bytes)" | El delegado del `ErrorHandlingDeserializer` devuelve `InboundOrder(message, raw)`, y el serializador del productor escribe `raw` tal cual. | Sin esto, un mensaje legible pero inválido llegaría a la DLT re-serializado y no idéntico al original. Para un mensaje ilegible, el recoverer ya usa los bytes de la `DeserializationException`. |
| K3 | Categoría `UNEXPECTED` | No está en la tabla §5.2 | Se agregó a `ErrorCategory`. Un error no previsto (un bug) se reintenta como PERSISTENCE y, si persiste, va a la DLT con `x-error-category: UNEXPECTED` y solo el nombre de la clase como causa. | La tabla no cubre el caso, y etiquetarlo como PERSISTENCE mandaría a investigar Mongo. Confirmado el 2026-10-28. |
| K4 | `x-attempts` | "Cantidad de intentos realizados" | Entregas del mensaje (`kafka_deliveryAttempt`): 1 para lo no reintentable, 6 para PERSISTENCE agotada. Los intentos HTTP quedan en la causa (`... after 3 attempt(s)`). | Una sola definición del header para todas las categorías. |
| K5 | Formato de headers | No especificado | Todos los `x-*` son texto UTF-8. Se excluyen los `kafka_dlt-*` de Spring, que incluyen stack trace y mensaje crudo. **Sí queda** `kafka_deliveryAttempt` (entero binario), copiado del registro original. | Legibles con `kafka-console-consumer --property print.headers=true`. `kafka_deliveryAttempt` no trae datos ni trazas; quitarlo exigiría reemplazar la copia de headers del recoverer. |
| K6 | Números con tipo incorrecto | "quantity > 0 entero" es validación (§5.1) | `quantity: 2.5`, `eventVersion: 1.0` o `unitPrice: "35.50"` (string) son **DESERIALIZATION**, no VALIDATION. | Jackson no puede representarlos en el DTO sin truncar ni convertir. Ambas categorías van a la DLT sin reintento: solo cambia la etiqueta. |
| K7 | `max.poll.records` | No especificado | 10 (configurable). | Cada registro puede tardar hasta ~15 s. Con el default de 500, un lote superaría `max.poll.interval.ms` (5 min) y provocaría un rebalanceo. |
| K8 | Fábrica del listener | Autoconfig + `spring.threads.virtual.enabled` | Fábrica, consumer y producer factory propios en `KafkaConfig`. Los hilos del listener son virtuales (`SimpleAsyncTaskExecutor.setVirtualThreads`), con concurrencia 3 (= particiones). | Ack mode, error handler, serializadores y DLT quedan en un solo archivo legible. Al definir la fábrica propia, la propiedad de Boot no aplica, así que los virtual threads se configuran explícitamente. |
| K9 | Publicación inmediata | `acks=all`, idempotencia | Envío síncrono, con espera máxima de 10 s (`delivery.timeout.ms=10000`, `request.timeout.ms=5000`). | Solo tras la confirmación del broker se puede marcar PUBLISHED. Con el broker caído, el listener se bloquea como máximo 10 s y el outbox queda PENDING. |
| K10 | Payload publicado | "Se lee del documento persistido" | El camino rápido y el relay publican el mismo `outbox.payload` (mismo mapper y misma serialización `ProcessedEventJson`). Los importes conservan la escala (`1836.00`). | Bytes idénticos por ambos caminos. Verificado contra `contracts/events/orders.processed.v1.schema.json` en tests unitarios y de integración. |
| K11 | `OutboxRelay` | Cada 5 s, PENDING > 10 s | `SchedulingConfigurer` con fixed delay (las pasadas no se solapan), lotes de 100 ordenados por `outbox.createdAt`. Ante el primer error corta el lote y reintenta en la siguiente pasada. Sin lease. | Si el broker cae, el resto del lote fallaría igual. El lease es la evolución que ya prevé §7.2. |
| K12 | `eventId` de salida | Los ejemplos usan ULID | UUID v4 (`IdGenerator` en `ApplicationConfig`). | Lo único que exige la propuesta es que sea único y estable, y el JDK lo resuelve sin dependencias. |
| K13 | order-processor en compose | Nota I7 | Agregado (imagen `eclipse-temurin:21.0.12_8-{jdk,jre}-alpine-3.24`, usuario sin privilegios), con `restart: on-failure`. **Sin healthcheck** hasta el hito 8 (actuator). Las variables usan la forma de relaxed binding de Spring (`ORDERPROCESSOR_HTTP_CLIENTSBASEURL`). | Cierra I7. Un `ORDER_PROCESSOR_HTTP_CLIENTS_BASE_URL` no enlazaría con `order-processor.http.clients-base-url`. |
| K14 | Tests de recuperación | "Relay del outbox tras un fallo de publicación" | Los fallos se inyectan con beans `@Primary` que envuelven los adaptadores reales (publicador, store), según el `orderId`. No se detiene el broker ni Mongo. | Deterministas y rápidos. Detener contenedores a mitad del test vuelve flaky la suite. Kafka, Mongo y el relay siguen siendo reales. |
| K15 | Aislamiento de los IT | — | Cada clase IT cierra su contexto (`@DirtiesContext`) y cada test usa `orderId` únicos. Los contenedores se comparten por JVM. | Dos contextos vivos con el mismo group id se repartirían las particiones y se robarían mensajes. |
| K16 | Orden entre commit y publicación | §5.1: escritura (6), **commit del offset (7)**, publicación (8) | El caso de uso publica dentro del listener, y `AckMode.RECORD` confirma el offset cuando el listener termina: escritura → publicación inmediata → `markPublished` → commit. | La condición de §6.4 se cumple igual: el estado es durable antes de publicar. Si el proceso cae después de publicar y antes del commit, la reentrega da `DUPLICATE` y no republica; si tampoco llegó a marcar `PUBLISHED`, el relay republica con el mismo `eventId`. El costo es que el broker caído retiene el commit hasta 10 s (K9). Detectado al redactar el resumen del hito 9. Confirmado el 2026-10-28. |

## Observabilidad de order-processor (hito 8)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| O1 | Formato de logs | "Logs JSON" | *Structured logging* nativo de Spring Boot 3.5 (`logging.structured.format.console=logstash`), sin dependencias. El MDC y los pares clave-valor de SLF4J salen como campos de primer nivel. | Una línea JSON por evento, sin `logstash-logback-encoder`. |
| O2 | Una línea por transición | `received`, `validated`, `enriched`, `evaluated`, `persisted`, `published`, `dlt`, `duplicate`, `stale` | `TransitionLog` pone el nombre en `message` y en `transition`, y los detalles como pares clave-valor (`status`, `category`, `cause`, `outboxEventId`, `path`). Una validación o un enriquecimiento fallidos usan la misma transición en `WARN` con `valid=false` / `ok=false`. Se agregó `publication_pending` (fallo del camino rápido o del relay). `published` lleva `path=immediate\|relay`. | Filtrar por `transition` reconstruye el recorrido de un pedido (paso 2 del runbook del anexo). `VERSION_CONFLICT` no tiene línea propia: la línea `dlt` lleva la categoría. |
| O3 | MDC | `orderId`, `eventId`, `eventVersion`, `partition`, `offset`, `outcome` | El listener lo pone al recibir y lo limpia al terminar. El recoverer de la DLT lo vuelve a poner, porque corre después. `outcome` lo agrega el caso de uso al evaluar. Las llamadas HTTP en paralelo **heredan el MDC** (copia explícita en `withCurrentMdc`). Para un mensaje ilegible, `orderId` sale de la clave. | El MDC es por hilo: sin la copia, los logs de reintentos HTTP de los virtual threads saldrían sin `orderId`. |
| O4 | Líneas ajenas con MDC | — | La primera publicación crea el productor Kafka de forma perezosa, y sus ~6 líneas de arranque salen con el MDC del pedido que la disparó. Ocurre una vez por proceso. | Inocuo. Evitarlo exigiría crear el productor al arrancar solo por estética del log. |
| O5 | Métricas de negocio | Anexo | Puerto `OrderMetrics` (`application/port/out`) con adaptador Micrometer: `orders_processed_total{status,market}` (incluye `TECHNICAL_FAILURE`, solo escrituras aplicadas), `orders_rejected_total{reason}` (una por razón), `orders_duplicates_total` y `orders_stale_total` (chequeo temprano o escritura condicional). | `application/` no depende de Micrometer. Las etiquetas son enums: cardinalidad acotada. |
| O6 | Métricas técnicas | Anexo | Se registran directamente en infraestructura: `http_client_retries_total{target}` (un incremento por reintento, no por intento), `orders_dlt_total{category}` (solo tras un envío confirmado), `orders_processing_duration_seconds` (histograma, recepción → resultado, en el listener) y `outbox_pending_age_seconds`. | Es donde ocurre cada hecho. La DLT se cuenta después del envío para no contar un envío que falló y se va a reintentar. |
| O7 | `outbox_pending_age_seconds` | Gauge | Lo actualiza el relay en cada pasada (cada 5 s) con el `createdAt` del PENDING más viejo. Si Mongo falla, conserva el último valor. | Un gauge que consulte Mongo en cada scrape acopla Prometheus a la base. Bajar a 0 con Mongo caído ocultaría justo el problema que la métrica debe alertar. |
| O8 | Lag del consumidor | Anexo | `MicrometerConsumerListener` en la fábrica propia: `kafka_consumer_fetch_manager_records_lag_max{client_id}`, uno por consumidor. | Con fábrica propia (K8), Boot no registra las métricas del cliente Kafka. |
| O9 | Servidor HTTP | No especificado | `spring-boot-starter-web` solo para actuator, en el **puerto 8081**, exponiendo `health` y `prometheus`. Tomcat con virtual threads (`spring.threads.virtual.enabled=true`). | 8080 es de products-api en el host. |
| O10 | Healthcheck | Nota K13 | `wget` a `/actuator/health` en compose. Es *liveness*: no hay health indicator de Mongo (no hay Spring Data) ni de Kafka. | El proceso arriba y el contexto levantado alcanzan para compose. Con Mongo caído al arrancar, la aplicación no arranca (M8). Un indicador de readiness es una mejora posible. |
| O11 | Métricas en tests | — | `ObservabilityIT` usa `@AutoConfigureObservability`. | En `@SpringBootTest`, Boot desactiva los exportadores de métricas por defecto y `/actuator/prometheus` responde 404. |

## No implementado

Lo que la propuesta menciona y esta versión no incluye.

| # | Propuesta | Estado | Qué hay en su lugar |
|---|---|---|---|
| G1 | §8.1: `contracts/events/dlt-headers.md` como fuente de verdad de la DLT | No existe | Los headers están en §5.5 de la propuesta, en el README y en `DeadLetterHeaders`. `OrderProcessingFlowIT` verifica que el registro de la DLT lleve exactamente esos headers. |
| G2 | §8.3: CI con verificación de compatibilidad de schemas respecto de `main` | No hay CI | Los tests y `contracts/validate.js` se corren localmente. Pact sigue siendo una evolución opcional. |
| G3 | §10: script de smoke E2E con `docker compose up` | No hay script | La sección "Probar el sistema a mano" del README, con comandos verificados sobre un stack recién creado el 2026-10-26. Es el recorte que §12 dejaba para el final. |
| G4 | §7.2: lease del relay (`outbox.lockedUntil`) | No implementado, como prevé la propuesta | Publicación al menos una vez con `eventId` estable (K11). |

## Deuda técnica consciente

Atajos tomados a sabiendas, con el motivo y cuándo habría que pagarlos. Ninguno afecta las
reglas de negocio ni la idempotencia.

| # | Deuda | Por qué se aceptó | Cuándo pagarla |
|---|---|---|---|
| D1 | Sin autenticación entre servicios, Kafka sin TLS y Mongo sin usuario (S11, I4) | Fuera del alcance de una prueba local | Antes de cualquier entorno compartido |
| D2 | Kafka con un broker y factor de replicación 1; Mongo standalone (I1, I4). `WriteConcern.MAJORITY` (M6) equivale a un solo nodo | Suficiente para mostrar el flujo en compose | Antes de producción: RF 3 y replica set |
| D3 | Sin CI ni verificación de compatibilidad de schemas (G2) | Los tests corren localmente | Primer cambio con más tiempo (ver abajo) |
| D4 | Sin script de smoke E2E (G3) | Fue el recorte previsto en §12 | Junto con D3 |
| D5 | products-api no valida sus respuestas contra el OpenAPI (P8) | Exigiría una dependencia en un servicio que usa solo la librería estándar | Con un job de CI que valide las respuestas desde fuera, sin tocar el servicio |
| D6 | `/actuator/health` es solo liveness: no comprueba Mongo ni Kafka (O10) | Sin Spring Data no hay indicador de Mongo automático | Antes de desplegar con un orquestador que use readiness |
| D7 | Sin herramienta de replay desde la DLT | Los headers ya tienen todo lo necesario (§5.5) y el replay se puede hacer a mano | Antes de operar en producción |
| D8 | Relay del outbox sin lease (G4) | Con una instancia no hay competencia; con varias, la publicación sigue siendo al menos una vez con `eventId` estable | Al escalar a más de una instancia, si los consumidores no deduplican |
| D9 | `contracts/events/dlt-headers.md` no existe (G1) | Los headers están en §5.5 y los verifica `OrderProcessingFlowIT` | Cuando otro equipo consuma la DLT |
| D10 | El read timeout cubre hasta los headers, no el cuerpo (N5) | Limitación del `HttpClient` del JDK; el presupuesto de 12 s acota el peor caso | Si una API empieza a devolver cuerpos grandes o lentos |

## Riesgos residuales

Lo que puede salir mal aun con todo lo implementado funcionando como se diseñó.

| # | Riesgo | Impacto | Qué lo mitiga hoy | Cómo se detecta |
|---|---|---|---|---|
| R1 | Un consumidor de `orders.processed.v1` que no deduplica por `eventId` | Ve dos veces el mismo resultado tras una republicación del relay | El `eventId` es estable (ADR 0002) y el schema de salida lo declara así | Del lado del consumidor; no es observable desde order-processor |
| R2 | Una revisión nueva reemplaza a la anterior mientras su evento sigue `PENDING`, o la reemplaza un `TECHNICAL_FAILURE` (M3) | Los consumidores nunca ven el resultado intermedio | Trade-off aceptado en §7.3: el estado final es el correcto | `outbox_pending_age_seconds` alto antes de la revisión nueva |
| R3 | Mongo caído durante más de ~1,5 min | Tras 6 entregas (§5.3: 62 s de backoff más hasta 5 s de selección de servidor por intento), los mensajes van a la DLT como `PERSISTENCE` y la partición se vacía hacia allí | Reintentos con backoff y `serverSelectionTimeoutMS=5000` (M7) | `orders_dlt_total{category="PERSISTENCE"}`; se recupera con replay manual (D7) |
| R4 | Un bug de cálculo que ya aprobó pedidos | Reprocesar el mismo evento da `DUPLICATE` y no corrige nada | Tests parametrizados de impuestos, descuento y redondeo | Conciliación de totales; la corrección exige una revisión nueva del productor |
| R5 | Un proveedor agrega un valor de enum sin avisar (S10) | Todos los pedidos con ese cliente o producto quedan `TECHNICAL_FAILURE` y van a la DLT | Política S10: no se rechaza por un problema técnico | `orders_dlt_total{category="EXTERNAL_PERMANENT"}`; replay tras actualizar el consumidor |
| R6 | APIs lentas o con 429 sostenidos | La partición avanza a ritmo del presupuesto por mensaje y el lag crece (ADR 0003) | Timeouts, `Retry-After` con tope, presupuesto de 12 s | `kafka_consumer_fetch_manager_records_lag_max`, `http_client_retries_total` |
| R7 | Pinning de virtual threads en Java 21 | Menos concurrencia efectiva si una librería bloquea dentro de `synchronized` | Concurrencia baja (3 listeners más las llamadas de un pedido) | No medido; ver ADR 0003 |
| R8 | Un 5xx que en realidad es permanente (501, 505) | Se reintenta 3 veces antes de fallar (N1) | El costo es acotado: unos segundos por mensaje | `http_client_retries_total{target}` |

## Siguiente cambio con más tiempo

**CI con el smoke E2E y la verificación de compatibilidad de contratos** (D3 y D4).

Es lo primero porque protege todo lo demás. Hoy la evidencia de que el sistema funciona son
comandos que alguien tiene que acordarse de correr. Con CI, cada PR corre los tests de los tres
servicios y la validación de `contracts/`, y un job falla si un cambio rompe un schema `v1`
respecto de `main` (§8.3). Al mergear, el smoke levanta compose, publica los eventos del README y
verifica Mongo, `orders.processed.v1` y la DLT. Cualquier cambio posterior, incluidos los de esta
lista, se puede hacer con red de seguridad.

Después, en este orden:

1. **Herramienta de replay desde la DLT** (D7). Es lo que hace operables R3 y R5: hoy se
   recuperan a mano.
2. **Readiness con Mongo y Kafka** (D6), junto con los umbrales de alerta de
   [`technical-leadership.md`](technical-leadership.md) §7.
3. **Caché de productos con expiración** (§9 de la propuesta). Reduce R6 sin cambiar el modelo
   de concurrencia.
4. **Lease del relay** (D8), solo cuando se despliegue más de una instancia.
