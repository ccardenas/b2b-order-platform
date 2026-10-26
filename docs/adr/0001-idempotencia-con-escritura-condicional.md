# ADR 0001 — Idempotencia con escritura condicional en MongoDB

- **Estado:** aceptada, implementada
- **Fecha:** 2026-10-26
- **Referencias:** propuesta §6.2–§6.4 y §11; notas de implementación N13, M2, M3, M6, M7

## Contexto

Kafka entrega cada mensaje **al menos una vez**. order-processor va a ver el mismo evento
repetido (reentregas tras un rebalanceo o una caída antes del commit del offset), revisiones de
un pedido fuera de orden y, si el productor se equivoca, dos eventos distintos con la misma
revisión. Con varias instancias, o tras un rebalanceo a mitad de proceso, dos copias del mismo
pedido pueden procesarse **a la vez**.

El requisito es un solo efecto de negocio por `(orderId, eventVersion)` y que un resultado más
nuevo nunca sea sobrescrito por uno más viejo. La garantía no puede depender del orden de Kafka
ni de que el particionado sea correcto.

## Decisión

El documento del pedido usa `_id = orderId`, y el resultado se guarda con **un único
`updateOne` con upsert** cuyo filtro solo coincide si la escritura está permitida:

```js
{ _id: orderId,
  $or: [ { eventVersion: { $lt: incomingVersion } },
         { eventVersion: incomingVersion, eventId: incomingEventId, status: "TECHNICAL_FAILURE" } ] }
```

- Si no hay documento, el upsert inserta.
- Si hay un documento y la condición se cumple, lo actualiza.
- Si hay un documento y la condición **no** se cumple, el upsert intenta insertar otro con el
  mismo `_id` y MongoDB responde **E11000**. El índice único de `_id` es el árbitro: entre
  escrituras concurrentes, solo una gana.

Un E11000 **no es un error**: significa "no aplicado". El adaptador relee el documento y lo
clasifica con `RevisionClassifier`, una función pura del dominio:

| Persistido vs. entrante | Resultado | Qué hace el listener |
|---|---|---|
| Misma versión, mismo `eventId` | `DUPLICATE` | Confirma el offset |
| Misma versión, distinto `eventId` | `VERSION_CONFLICT` | DLT y confirma el offset |
| Versión persistida mayor | `STALE` | Confirma el offset |

Ese mismo clasificador hace un chequeo temprano antes de llamar a las APIs, para no gastar
llamadas en un duplicado evidente. Es solo una optimización: la garantía es la escritura.

El offset se confirma solo después de un estado durable (`AckMode.RECORD`, auto-commit
desactivado, `WriteConcern.MAJORITY`).

## Alternativas descartadas

- **`exists` y luego `save`:** dos operaciones separadas. Dos hilos pueden ver "no existe" y
  guardar los dos. El enunciado lo descarta explícitamente.
- **Lock distribuido (Redis u otro):** si el lock expira a mitad del proceso, por una pausa de
  GC o una llamada lenta, dos instancias escriben igual. Además agrega un componente que operar.
- **Transacción multi-documento o colección de "eventos procesados":** requiere replica set y
  una segunda colección que mantener coherente con la primera. La atomicidad de un solo
  documento ya alcanza.
- **Confiar en el particionado por `orderId`:** reduce la concurrencia, pero no la elimina
  (rebalanceos, productor sin clave). Se usa como ayuda, no como garantía.

## Consecuencias

**Positivas**

- La corrección se reduce a una sola operación atómica, legible en el código y verificable
  contra un Mongo real.
- No hay lock, colección extra ni infraestructura adicional. Funciona con Mongo standalone.
- Un `TECHNICAL_FAILURE` se puede reprocesar con el mismo evento (por ejemplo, un replay desde
  la DLT), porque la segunda rama del `$or` lo admite.

**Negativas y riesgos**

- Confundir el E11000 con un error de persistencia mandaría duplicados a la DLT. Se mitiga con
  el mapeo explícito en `MongoOrderResultStore` y un test dedicado.
- Entre el E11000 y la relectura, otra instancia puede dejar el documento en un estado
  escribible (por ejemplo, un `TECHNICAL_FAILURE` del mismo evento). En ese caso se reintenta
  el upsert hasta 3 veces (nota M2). **Ese camino no tiene test**: forzar la intercalación de
  forma determinista exige instrumentar el driver.
- Una revisión nueva reemplaza el resultado completo, incluido un outbox todavía `PENDING`. Ver
  [ADR 0002](0002-outbox-embebido-en-el-documento-del-pedido.md).

## Cómo se verifica

`MongoOrderResultStoreIT`, contra `mongo:8.0.34` con Testcontainers:

- `theSameEventProcessedByTenThreadsLeavesOneDocumentAndOneOutboxEvent` (10 hilos, mismo evento)
- `twoConcurrentEventsWithTheSameVersionLeaveOneWinnerAndOneConflict`
- `anOlderVersionAfterANewerOneIsStaleAndLeavesTheNewerIntact`
- `theSameEventIsAcceptedAgainOverItsOwnTechnicalFailure`
- `anUnavailableDatabaseIsAStorageFailureNotADuplicate`

En las pruebas de mutación del hito 6, quitar la condición del filtro o el manejo del E11000
hizo fallar estos tests.
