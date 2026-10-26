# ADR 0002 — Outbox embebido en el documento del pedido

- **Estado:** aceptada, implementada
- **Fecha:** 2026-10-26
- **Referencias:** propuesta §7.1–§7.3 y §11; notas de implementación N14, M3, M4, K9, K10, K11, O7

## Contexto

Cada resultado aprobado o rechazado se persiste en MongoDB y se publica en
`orders.processed.v1`. Son dos sistemas **sin transacción común**. Si el proceso cae entre los
dos pasos, puede quedar:

- un pedido persistido que nunca se publica, o
- un evento publicado cuyo pedido no quedó persistido.

Además, la publicación puede repetirse (reintentos, reentregas) y los consumidores no deben ver
dos efectos de negocio distintos para la misma revisión.

## Decisión

El evento de salida se guarda **dentro del mismo documento del pedido**, en la misma escritura
condicional del [ADR 0001](0001-idempotencia-con-escritura-condicional.md):

```json
"outbox": { "eventId": "…", "status": "PENDING", "createdAt": "…",
            "publishedAt": null, "publishAttempts": 0, "payload": { …orders.processed.v1… } }
```

MongoDB garantiza la atomicidad de un documento, así que el resultado y su evento se guardan
juntos o no se guarda ninguno. Luego hay dos caminos de publicación:

1. **Inmediato:** justo después de escribir, el listener publica de forma síncrona (`acks=all`,
   productor idempotente, espera máxima de 10 s) y marca `PUBLISHED` con un update condicionado
   a `outbox.eventId` y `status = PENDING`. Si falla, el outbox queda `PENDING` y el offset se
   confirma igual, porque el estado ya es durable.
2. **Recuperación:** un `OutboxRelay` programado cada 5 s busca los `PENDING` con más de 10 s
   (índice parcial sobre `outbox.status = PENDING`) y los publica.

Los dos caminos publican los mismos bytes: el payload se arma del documento persistido y pasa
por el mismo serializador (`ProcessedEventJson`). El `eventId` de salida se genera una vez, al
escribir, y es estable en todas las republicaciones. Un `TECHNICAL_FAILURE` se persiste sin
outbox, porque el contrato de salida solo admite `APPROVED` y `REJECTED`.

## Alternativas descartadas

- **Publicar primero y persistir después, o persistir y publicar sin outbox:** cualquier caída
  entre los dos pasos deja uno de los estados inconsistentes del enunciado.
- **Transacciones de Kafka:** cubren lo que se escribe en Kafka, no la escritura en Mongo. No
  existe una transacción distribuida real entre ambos, y daría una falsa sensación de garantía.
- **Colección `outbox` separada con transacción multi-documento:** funciona, pero requiere
  replica set y transacciones para lograr lo que un solo documento ya da.
- **CDC (Change Streams o Debezium):** más infraestructura y más componentes que operar. Es la
  evolución razonable si el relay deja de escalar.

## Consecuencias

**Positivas**

- No hay estado posible con un pedido persistido y sin evento pendiente, ni un evento publicado
  sin pedido persistido.
- No necesita replica set, transacciones ni componentes extra.
- En el caso normal el evento sale de inmediato. El relay solo trabaja ante fallos, y
  `outbox_pending_age_seconds` alerta si se acumulan pendientes.

**Negativas y riesgos**

- **Publicación al menos una vez:** si el proceso cae entre publicar y marcar `PUBLISHED`, el
  relay republica. El `eventId` es el mismo y los consumidores deben deduplicar por él.
- **Relay sin lease:** con varias instancias, dos relays pueden publicar el mismo pendiente. Es
  la misma garantía de al menos una vez. Si llega a ser un problema, se agrega
  `outbox.lockedUntil` con `findOneAndUpdate` (§7.2).
- **Una revisión nueva reemplaza un evento pendiente:** si la revisión 2 llega mientras el
  evento de la revisión 1 sigue `PENDING`, el de la revisión 1 nunca se publica y los
  consumidores ven directamente el estado más reciente. Es aceptado y documentado (§7.3).
- **Latencia de recuperación:** hasta unos 15 s (10 s de antigüedad mínima más el intervalo de
  5 s) tras un fallo de publicación.
- El listener se bloquea hasta 10 s por publicación con el broker caído. Lo acota el
  presupuesto por mensaje.

## Cómo se verifica

- `RecoveryIT.whenTheImmediatePublicationFailsTheRelayPublishesTheSameEventOnce`: con Kafka y
  Mongo reales, la publicación inmediata falla, el relay publica una sola vez y con el mismo
  `outbox.eventId`.
- `OrderProcessingFlowIT`: flujo completo. El evento publicado se valida contra
  `contracts/events/orders.processed.v1.schema.json`.
- `MongoOrderResultStoreIT.markPublishedOnlyTouchesTheMatchingPendingEvent` y
  `aTechnicalFailureIsPersistedWithoutOutboxAndRemovesThePreviousOne`.
- `ProcessedEventJsonTest`: el JSON publicado cumple el contrato y conserva la escala de los
  importes. Los dos caminos usan este mismo serializador sobre el mismo `outbox.payload`; no hay
  un test que compare los bytes de ambos caminos entre sí.
