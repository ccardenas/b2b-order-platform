# Liderazgo técnico — cómo conduciría la implementación

Plan para construir esta plataforma con un equipo de **cuatro personas, Tech Lead incluido**. No
es una metodología: es lo mínimo que hay que acordar para que el equipo avance en paralelo sin
perder coherencia. El diseño técnico está en [`architecture-proposal.md`](architecture-proposal.md)
y no se repite aquí.

## Equipo

| Rol | Perfil | Además de programar |
|---|---|---|
| **TL** (Tech Lead) | Java senior | Custodia de `contracts/`, integración de extremo a extremo, ADRs |
| **Dev Java** | Java | Segunda persona que conoce a fondo order-processor |
| **Dev Go** | Go | Entorno local (`docker-compose.yml`) |
| **Dev TS** | Node / TypeScript | Pipeline de CI y validación de contratos |

Todos revisan PRs fuera de su stack. No se espera que la persona de Go escriba Java, pero sí que
pueda leer el adaptador que consume su API y detectar si interpreta mal su contrato.

---

## 1. División inicial del trabajo y ownership

El reparto es por **resultado de negocio**, no por capa. Cada persona es dueña de una pieza y
también de que esa pieza funcione **dentro del flujo completo**, no solo en sus tests.

| Persona | Es dueña de | Y responde por este escenario de extremo a extremo |
|---|---|---|
| **TL** | Idempotencia y adaptador Mongo (hito 6), listener, offsets, DLT, outbox y relay (hito 7) | Duplicado, `STALE`, `VERSION_CONFLICT` y caída entre Mongo y Kafka, verificados en compose |
| **Dev Java** | Dominio (hito 1), caso de uso y adaptadores HTTP (hito 5), observabilidad (hito 8) | Un pedido aprobado y uno rechazado con importes correctos, trazables por `orderId` en logs y métricas |
| **Dev Go** | products-api (hito 2) y `docker-compose.yml` (hito 4) | Cada fallo inyectado de productos (`PRD-*`) termina en el estado esperado de order-processor |
| **Dev TS** | clients-api (hito 3) y CI | Cada fallo inyectado de clientes (`CLI-*`) termina en el estado esperado de order-processor |

Reglas que evitan fragmentar la responsabilidad:

- **El dueño de una API revisa obligatoriamente el adaptador que la consume** en order-processor
  (`ProductsClient`, `ClientsClient`). Es quien mejor sabe qué significa un 404 o un 429 de su
  servicio.
- **Nada de "mi parte funciona".** Una tarea está terminada cuando su escenario pasa en el stack
  completo, no cuando pasan los tests unitarios.
- **La idempotencia no puede depender de una sola persona.** El TL la implementa y Dev Java la
  revisa a fondo. Por eso Dev Java toma la observabilidad: obliga a recorrer todas las
  transiciones del flujo.

## 2. Dependencias y orden de integración

```
Fase 0  contratos ─────────────────────────────────┐  (bloquea a todos: día 1-2)
                                                   ▼
Fase 1  dominio (Dev Java)   products-api (Dev Go)   clients-api (Dev TS)   compose (Dev Go)
            │                       │                       │                    │
            ▼                       └──────────┬────────────┘                    │
Fase 2  adaptadores HTTP con WireMock          ▼                                 │
        (Dev Java)                    I1: APIs en compose cumplen el OpenAPI ◄───┘
        adaptador Mongo con Testcontainers (TL)
            │
            ▼
        I2: order-processor contra las APIs reales en compose, con fallos inyectados
            │
            ▼
Fase 3  listener, DLT, outbox, relay (TL)  ──►  I3: flujo completo Kafka → Mongo → Kafka
            │
            ▼
Fase 4  observabilidad (Dev Java), smoke E2E (TL), runbooks (todos)
```

Por qué nadie espera a nadie:

- **El dominio no depende de nada.** Es Java puro y se prueba solo (ArchUnit lo garantiza).
- **order-processor no necesita las APIs para avanzar.** Los stubs de WireMock se arman con los
  fixtures de `contracts/fixtures/http/`, los mismos con los que las APIs validan sus
  respuestas. Si una API cambia su forma, se rompen ambos lados a la vez.
- **Las APIs no dependen de order-processor.** Se prueban contra su propio OpenAPI.

Los puntos de integración tienen un criterio de entrada explícito:

| # | Integración | Se considera lograda cuando |
|---|---|---|
| I1 | APIs en compose | `docker compose up --wait` en verde y las respuestas de cada ID semilla coinciden con los fixtures |
| I2 | order-processor ↔ APIs | Cada ID de fallo (`PRD-FAIL-503`, `PRD-SLOW`, `CLI-FAIL-429`, `CLI-BAD-ENUM`, etc.) produce la clasificación de §5.2 contra las APIs reales |
| I3 | Flujo completo | Un evento publicado en `orders.created.v1` termina en Mongo y en `orders.processed.v1`, y un duplicado no genera un segundo efecto |

Integrar temprano es deliberado: compose existe desde la fase 1 para que I1 ocurra antes de que
haya código de integración que rehacer.

## 3. Contratos que acordaría antes de programar

Se acuerdan en la fase 0, como PRs sobre `contracts/`, y se mergean antes de abrir trabajo
paralelo. Son lo único que **bloquea** a otros integrantes, así que se cierran primero.

| Contrato | Archivo | Qué hay que dejar fijado |
|---|---|---|
| Evento de entrada | `contracts/events/orders.created.v1.schema.json` | Campos obligatorios, clave del mensaje (`orderId`) y significado de `eventVersion` (revisión del pedido, supuesto S1) |
| Evento de salida | `contracts/events/orders.processed.v1.schema.json` | `totals: null` en rechazos (S7), un único `reason` (S9), `eventId` estable |
| APIs HTTP | `contracts/http/*.openapi.yaml` | Rutas, códigos de respuesta, enums y su política de evolución (S10) |
| Error HTTP | `contracts/http/error.schema.json` | `{"error":{"code","message","traceId"}}` y catálogo de códigos estables |
| Headers de la DLT | §5.5 de la propuesta | Nombres y formato de cada header, que operación usa para el replay |
| Inyección de fallos | `contracts/fixtures/http/` | IDs semilla por fallo y comportamiento de cada uno |

Además de los archivos, hay acuerdos **semánticos** que un schema no expresa y que conviene
escribir igual:

- **Dinero:** número JSON en el contrato, `BigDecimal` en Java, escala 2 y `HALF_UP` por
  importe. Una diferencia de criterio aquí no rompe ningún test de contrato, pero sí los totales.
- **Latencia de las APIs:** p99 por debajo del read timeout de order-processor (2 s, §5.3). Es
  un contrato entre equipos aunque no viva en el OpenAPI.
- **`traceId`:** se toma del header `X-Trace-Id` si viene y se genera si no (P7 y C7 de las
  notas de implementación). Igual en ambas APIs.

## 4. Branches, pull requests y code review

**Branches.** Trunk-based: `main` siempre compila, pasa CI y se puede desplegar. Ramas cortas
desde `main` (`feat/products-fault-injection`), de uno o dos días como máximo. Si una tarea
es más larga, se parte; no se mantiene una rama viva una semana.

**Pull requests.**

- Pequeños: menos de ~400 líneas cambiadas, sin contar fixtures ni código generado. Un PR que
  cambia un contrato no incluye además la implementación.
- Mensajes en Conventional Commits (`feat(order-processor): ...`) y *squash merge*, para que el
  historial de `main` cuente la evolución de la solución.
- La descripción responde tres cosas: **qué cambia**, **cómo se probó** (con el comando) y
  **qué riesgo tiene y cómo se revierte**.
- PR en borrador temprano cuando hay una decisión de diseño en juego, antes de escribir todo el
  código.

**Revisiones requeridas.**

| Qué toca el PR | Aprobaciones |
|---|---|
| Cualquier cambio | 1, de alguien que no sea el autor |
| `contracts/` | Dueño del contrato **y** al menos un consumidor (CODEOWNERS) |
| Adaptador de una API en order-processor | Dueño de esa API |
| Idempotencia, offsets, outbox, clasificación de errores, DLT | TL y Dev Java (dos personas) |

**Cómo se revisa.** El objetivo de una revisión es encontrar riesgo, no imponer estilo. Cada
comentario se marca como:

- **Bloqueante:** con evidencia concreta. Un caso que rompe (input → resultado incorrecto), un
  contrato violado, un camino de fallo sin test, un dato sensible expuesto.
- **Sugerencia:** preferencia o mejora. El autor decide si la aplica y no bloquea el merge.

Lo que el formateador o el linter pueden detectar no se discute en la revisión. Las revisiones
se responden en el mismo día hábil: un PR detenido bloquea más que un PR imperfecto.

## 5. Controles mínimos de CI

Un pipeline por componente, con filtro por rutas para que un cambio en Go no corra los tests de
Java. Todos los jobs son **checks requeridos** para mergear.

| Job | Se ejecuta si cambia | Comando |
|---|---|---|
| contracts | `contracts/**` | `npm ci && npm run validate` + compatibilidad hacia atrás contra `main` |
| order-processor | `order-processor/**`, `contracts/**` | `./mvnw verify` (unitarios, WireMock, ArchUnit y Testcontainers) |
| products-api | `products-api/**`, `contracts/**` | `gofmt -l`, `go vet ./...`, `go test -race ./...` |
| clients-api | `clients-api/**`, `contracts/**` | `npm ci`, lint, `npm test`, `npm run test:e2e`, `npm run build` |
| imágenes | cualquier `Dockerfile` o `docker-compose.yml` | `docker compose build` |
| secretos | todo | Escaneo de secretos en el diff |

Un cambio en `contracts/` dispara los tests de los tres servicios: es la forma barata de saber
si un cambio de contrato rompe a un consumidor antes de mergearlo.

**Compatibilidad de schemas (§8.3).** Para los archivos `*.v1.*`, el job falla si el cambio
elimina un campo, lo vuelve obligatorio, cambia un tipo o quita un valor de enum. Agregar un
valor de enum no falla el build, pero exige la aprobación de los consumidores (S10).

Objetivo de duración: menos de 10 minutos. Si se pasa, se paraleliza antes de recortar tests.
El smoke E2E sobre compose corre al mergear a `main`, no en cada PR.

## 6. Criterios para aprobar o rechazar una decisión técnica

Una propuesta (dependencia, patrón, cambio de diseño) se evalúa con estas preguntas, en orden:

1. **¿Qué problema concreto resuelve?** Tiene que poder señalarse en la propuesta, en un test que
   falla o en una métrica. "Podría hacer falta" no alcanza.
2. **¿Qué evidencia hay?** Un test, una prueba de concepto, una medición. Ante dos opiniones sin
   evidencia, se elige la más simple.
3. **¿Es reversible?** Si se revierte en un PR, decide quien es dueño del componente. Si no
   (formato de datos persistidos, contratos, semántica de entrega), requiere un ADR.
4. **¿Cuánto cuesta operarlo?** Cada componente nuevo es algo más que monitorear, actualizar y
   entender a las 3 de la mañana.
5. **¿Es local o transversal?** Ver la tabla siguiente.

Ejemplos de este proyecto, con el motivo registrado:

| Decisión | Resultado | Motivo |
|---|---|---|
| Retry topics no bloqueantes | Rechazada | Rompen el orden por `orderId`, que es parte de la corrección (propuesta §11) |
| Spring Data MongoDB | Rechazada | El upsert condicional necesita control fino del filtro y del E11000 (M1) |
| resilience4j | Rechazada | Retry con backoff y `Retry-After` cabe en una clase con tests; no justificaba la dependencia (N4) |
| Gin / Echo en Go | Rechazada | `net/http` de Go 1.22 cubre el ruteo |
| `400` y `503` en el OpenAPI de products-api | Aprobada | Cambio aditivo, necesario para un test y un fallo semilla ya acordados (P1) |

### Estándar compartido o decisión de cada equipo

Solo se estandariza lo que **cruza un límite entre servicios** o lo que **opera alguien que no
escribió el código**. El resto lo decide cada equipo.

| Estándar compartido (cambia por ADR) | A criterio de cada equipo |
|---|---|
| Contratos de eventos y HTTP, y sus reglas de versionado | Framework y librerías internas |
| Contrato de error y catálogo de códigos | Estructura de paquetes y carpetas |
| `X-Trace-Id` y campos de correlación en logs (`orderId`, `eventId`, `traceId`) | Librería de logging, siempre que emita JSON |
| `GET /health` | Herramientas de test |
| Inyección de fallos: flag `FAULT_INJECTION_ENABLED` y convención de IDs | Cómo implementar la inyección de fallos (middleware, interceptor) |
| Política de timeouts, reintentos y clasificación de errores en el consumidor | Estrategia de caché interna, si alguna vez hace falta |

### Cómo evitar que los tres servicios repliquen las mismas abstracciones

- **Se comparten contratos, no código.** No hay librería común entre Java, Go y TypeScript. Cada
  servicio implementa el contrato de error de forma idiomática en unas pocas líneas, y el mismo
  `error.schema.json` verifica que todos digan lo mismo.
- **Las abstracciones se crean en el servicio que las necesita.** Por ejemplo, el retry vive en
  order-processor porque es el único que llama a otros servicios. Las APIs no tienen cliente HTTP
  ni capa de retry porque no lo necesitan.
- **Las APIs no copian la arquitectura del worker.** products-api y clients-api son lecturas
  simples: no tienen puertos de entrada, casos de uso ni mappers de dominio que no aporten nada.
  La complejidad se concentra donde está el problema.
- **Antes de extraer algo compartido, tiene que repetirse tres veces** y cambiar por los mismos
  motivos. Dos repeticiones parecidas suelen evolucionar distinto.

## 7. Plan de entrega incremental y rollback

**Entrega dentro del equipo.** Las fases de §2. Cada una termina en `main`, con CI en verde y
algo que se puede demostrar en compose. No hay ramas de integración largas.

**Salida a producción** (la plataforma es nueva y no reemplaza a un sistema existente):

| Etapa | Qué se activa | Para pasar a la siguiente |
|---|---|---|
| 1 | products-api y clients-api en producción, sin consumidores | Health, latencia p99 < 2 s y tasa de errores medidas con tráfico sintético |
| 2 | order-processor consume tráfico real y publica en `orders.processed.v1`, pero **ningún consumidor downstream lo usa todavía** | Una semana con tasa de DLT y de `TECHNICAL_FAILURE` dentro de umbral, `outbox_pending_age_seconds` estable y conciliación manual de una muestra de totales por mercado |
| 3 | Los consumidores downstream empiezan a leer el tópico, un mercado a la vez (filtrando por `market` del lado del consumidor) | Sin incidentes en el mercado anterior |

**Cuándo revertir.** Se acuerdan los umbrales antes de desplegar, no durante el incidente. Por
ejemplo: tasa de DLT por encima del 1 %, `VERSION_CONFLICT` distinto de cero sostenido,
`outbox_pending_age_seconds` creciendo durante más de 5 minutos o lag del consumidor creciendo
sin recuperarse.

**Cómo se revierte cada cosa.**

- **Código:** order-processor y las APIs no tienen estado propio, así que el rollback es volver a
  la imagen anterior. El consumer group conserva sus offsets, y reprocesar lo que quedó a medias
  es seguro porque la escritura es idempotente.
- **Documento en Mongo:** los cambios de forma son aditivos (*expand → migrate → contract*).
  Primero se despliega una versión que tolera el campo nuevo y después la que lo escribe, para
  que la versión anterior pueda leer lo que dejó la nueva.
- **Contratos:** se despliega primero el consumidor y después el productor. Un cambio
  incompatible va a `v2` en paralelo (§8.2), así que revertir es dejar de publicar en `v2`.

**Lo que un rollback no arregla.** Si una versión con un bug de cálculo ya aprobó pedidos con
totales incorrectos, volver a la imagen anterior y reprocesar **no** corrige esos pedidos: el
mismo `(orderId, eventVersion, eventId)` se clasifica como `DUPLICATE`, que es justamente lo que
garantiza la idempotencia. La corrección requiere una revisión nueva del pedido desde el
productor o un procedimiento de reparación explícito y auditado. Por eso la etapa 2 existe:
detectar errores de cálculo antes de que alguien dependa de ellos.

## 8. Principales riesgos técnicos y responsables

Cada riesgo tiene **una** persona responsable. Puede pedir ayuda, pero es quien responde si nadie
lo mitiga.

| Riesgo | Responsable | Mitigación |
|---|---|---|
| Regresión en idempotencia: un E11000 mal clasificado o un `exists` + `save` que se cuela | TL | Tests concurrentes con Testcontainers, revisión doble obligatoria (§4), ADR 0001 |
| Conocimiento concentrado en el TL (idempotencia, outbox) | TL | Dev Java revisa todo ese código y pasa a ser responsable de los runbooks de DLT y outbox |
| Totales incorrectos por precisión o redondeo | Dev Java | `BigDecimal` en todas las capas, tests de bordes (x.xx5) y tests parametrizados por mercado y categoría |
| Cambio de tasas fiscales sin aviso | Dev Java | Tabla de tasas aislada; un cambio requiere un PR con aprobación de negocio, no solo técnica |
| Deriva de contratos entre equipos | Dev TS | Job de compatibilidad en CI, fixtures compartidos, CODEOWNERS sobre `contracts/` |
| Latencia o 429 de products-api que bloquean particiones | Dev Go | SLO de latencia (§3), métrica de reintentos por destino y caché de productos como siguiente paso |
| Valor de enum nuevo de un proveedor | Dueño de la API | Anuncio previo; el consumidor se actualiza primero (S10) |
| Relay duplicando publicaciones con varias instancias | TL | `eventId` estable y deduplicación en los consumidores; lease del relay si se escala horizontalmente (G4) |
| Integración tardía que obliga a rehacer adaptadores | TL | Compose desde la fase 1 e integraciones I1–I3 con criterio explícito |
| Servicios sin autenticación (S11) | TL | Bloqueante para producción (ver §10) |

Los riesgos se revisan una vez por semana: 15 minutos, solo la tabla.

## 9. Cómo manejaría un desacuerdo técnico entre dos integrantes

1. **Separar el desacuerdo del PR.** Si la discusión supera dos o tres comentarios, se pasa a
   una conversación de 30 minutos. Los hilos largos en un PR no convergen.
2. **Cada persona escribe su opción** en pocas líneas: qué propone, qué riesgo reduce, qué cuesta
   y qué evidencia tiene. A menudo el desacuerdo se resuelve aquí: estaban resolviendo problemas
   distintos.
3. **Si la evidencia es barata, se consigue.** Un test que muestra el caso que rompe vale más que
   la discusión. Por ejemplo: "los retry topics rompen el orden" se demuestra con un test de
   integración en una tarde.
4. **Decide quien corresponde, según §6.** Si es reversible y local, el dueño del componente. Si
   es transversal o difícil de revertir, el TL, con un ADR que registre la opción descartada y su
   motivo.
5. **Se deja escrita la condición para revisarla.** "Si la latencia p99 supera X" o "si se
   despliega más de una instancia". Así quien perdió la discusión sabe qué evidencia la
   reabriría.
6. **Una vez decidido, todos lo implementan.** Se puede no estar de acuerdo y comprometerse igual.
   Reabrir la discusión en cada PR no está permitido; traer evidencia nueva, sí.

Si el TL es una de las dos partes, la decisión la toma igual, pero la otra persona escribe la
sección "alternativas consideradas" del ADR. Obliga a que su argumento quede bien representado.

## 10. Definición de terminado para la primera versión productiva

**Funcional**

- Las reglas de negocio del enunciado tienen tests: impuestos por mercado y categoría, exención,
  descuento mayorista, redondeo, elegibilidad y validaciones.
- Duplicado, revisión obsoleta, conflicto de versión, fallo transitorio, fallo definitivo y caída
  entre Mongo y Kafka tienen tests de integración.
- El smoke E2E corre contra compose después de cada merge a `main`.

**Contratos**

- Cada productor valida en sus tests que lo que emite cumple su schema u OpenAPI.
- CI rechaza un cambio incompatible en un contrato `v1`.

**Operación**

- Métricas del anexo de la propuesta con dashboard y alertas sobre DLT, `VERSION_CONFLICT`,
  antigüedad del outbox pendiente y lag del consumidor.
- Runbooks para "pedido perdido" (anexo de la propuesta) y para el replay desde la DLT, probados
  al menos una vez por alguien que no los escribió.
- Umbrales de rollback acordados (§7) y un rollback ensayado en un entorno de pruebas.

**Infraestructura y seguridad**

- Kafka con factor de replicación 3 y Mongo como replica set.
- Autenticación entre servicios, TLS en Kafka y usuarios en Mongo con permisos mínimos.
- Secretos fuera del repositorio, inyectados por el entorno.
- Una prueba de carga con el volumen esperado, que confirme que el presupuesto de tiempo por
  mensaje no provoca rebalanceos.

### Estado de este repositorio frente a esa definición

Este repositorio es la primera iteración, no la versión productiva. Lo que falta, con su
referencia en [`implementation-notes.md`](implementation-notes.md):

| Punto de la DoD | Estado |
|---|---|
| Reglas de negocio y escenarios de fallo con tests | Cumple |
| Productor valida lo que emite | Cumple en order-processor y clients-api; products-api valida con structs estrictos, no contra el YAML (P8) |
| Smoke E2E automatizado | Falta; hay un procedimiento manual en el README (G3) |
| CI y compatibilidad de contratos | Falta (G2) |
| Métricas | Cumple; faltan dashboard y alertas |
| Runbooks | Parcial: "pedido perdido" en la propuesta; replay desde la DLT manual y sin herramienta |
| Replicación, seguridad y secretos | Falta: un broker, Mongo standalone, sin autenticación (S11) |
| Prueba de carga | Falta |
| Lease del relay para varias instancias | Falta (G4); hoy la publicación es al menos una vez con `eventId` estable |
