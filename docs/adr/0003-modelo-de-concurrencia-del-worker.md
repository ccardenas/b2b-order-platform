# ADR 0003 — Modelo de concurrencia y procesamiento del worker

- **Estado:** aceptada, implementada
- **Fecha:** 2026-10-28
- **Referencias:** propuesta §5.3, §5.4, §6.1 y §11; notas de implementación I1, K7, K8, K9, N4, N11, N12, M7, O3

## Contexto

Por cada mensaje, order-processor hace una llamada a clients-api y una por cada ítem a
products-api. Cada llamada puede tardar hasta 2 s y reintentarse dos veces. Después escribe en
MongoDB y publica en Kafka. Casi todo el tiempo de un mensaje es **espera de I/O**, no CPU.

Hay tres requisitos que el modelo tiene que cumplir a la vez:

- **Orden por pedido:** una revisión vieja no puede procesarse después de una nueva del mismo
  `orderId` si llegaron en orden. Kafka solo ordena dentro de una partición.
- **Commit solo tras un estado durable** (§6.4): el offset de un mensaje no se confirma hasta
  que su resultado está escrito, enviado a la DLT o clasificado como duplicado o stale.
- **Sin rebalanceos provocados por la lentitud:** si un lote tarda más que
  `max.poll.interval.ms` (5 min), Kafka da por muerto al consumidor y reasigna sus particiones.

El enunciado pide justificar la elección entre programación reactiva, virtual threads o un
modelo tradicional.

## Decisión

**Listener bloqueante, un registro a la vez por partición, sobre virtual threads.**

- **Consumo:** `ConcurrentKafkaListenerContainerFactory` con concurrencia 3, igual al número de
  particiones de `orders.created.v1` (I1). Cada hilo del listener es un virtual thread (K8).
  Dentro de una partición los mensajes se procesan **en serie**, de principio a fin, antes de
  pasar al siguiente.
- **Offsets:** `enable.auto.commit=false` y `AckMode.RECORD`. El offset se confirma cuando el
  listener termina el mensaje, después de escribir y publicar (K16).
- **Enriquecimiento en paralelo:** el cliente y todos los productos se piden a la vez, cada
  llamada en su propio virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`), y se
  esperan todas en un orden fijo (N11). El código del caso de uso es secuencial y se lee de
  arriba abajo; la concurrencia queda en un solo método (`ProcessOrderUseCase.enrich`).
- **Tiempo acotado en cada nivel:**

  | Nivel | Límite | Dónde |
  |---|---|---|
  | Llamada HTTP | 500 ms de conexión, 2 s de respuesta, 3 intentos con backoff | `ResilientHttpClient` (§5.3) |
  | Enriquecimiento completo | 12 s; al agotarse cancela las llamadas pendientes y el pedido queda `TECHNICAL_FAILURE` transitorio | `ProcessOrderUseCase` (N12) |
  | Publicación inmediata | 10 s | Productor Kafka (K9) |
  | Lote de un poll | 10 registros (`max.poll.records`) | `KafkaConfig` (K7) |

  Con estos límites, un lote en el peor caso queda lejos de los 5 min de
  `max.poll.interval.ms`.

- **El orden de Kafka no es la garantía de corrección.** Si dos copias del mismo pedido se
  procesan a la vez (rebalanceo a mitad de mensaje, varias instancias, un productor que no usa
  `orderId` como clave), la escritura condicional del
  [ADR 0001](0001-idempotencia-con-escritura-condicional.md) decide cuál gana. El procesamiento
  secuencial por partición solo evita que eso pase en el caso normal.

## Alternativas descartadas

- **Programación reactiva (WebFlux, Reactor Kafka):** resuelve el mismo problema de I/O con
  menos hilos, pero a cambio de código asíncrono en todo el flujo, trazas de error más difíciles
  de leer y el MDC propagado a mano en cada operador. No hay un requisito de throughput que lo
  justifique, y los virtual threads dan la misma eficiencia con código lineal.
- **Pool fijo de hilos de plataforma:** funciona, pero obliga a dimensionar el pool
  (particiones × ítems por pedido) y a decidir qué pasa cuando se llena. Un virtual thread por
  llamada elimina esa decisión.
- **Retry topics no bloqueantes (`@RetryableTopic`):** liberan la partición mientras un mensaje
  espera su reintento, pero la revisión 2 de un pedido puede procesarse antes de que se
  reintente la revisión 1. Rompen el orden por `orderId` y agregan tópicos que operar.
- **Paralelismo dentro de una partición por clave** (por ejemplo, Confluent Parallel Consumer):
  mantiene el orden por `orderId` y procesa pedidos distintos en paralelo, pero complica el
  commit de offsets (hay que confirmar solo el prefijo terminado) y suma una dependencia. Es la
  evolución si las particiones dejan de alcanzar (ver "Cuándo revisarla").
- **Listener por lotes (batch listener):** permite agrupar escrituras, pero un fallo en un
  registro complica decidir qué offset confirmar. El cuello de botella es la espera de las APIs,
  no la escritura.

## Consecuencias

**Positivas**

- El flujo de un mensaje es código secuencial: se depura con un stack trace normal y se revisa
  leyendo de arriba abajo.
- La latencia de un pedido es la de su llamada más lenta, no la suma de todas.
- El orden por `orderId` se preserva sin mecanismos extra, y el commit tras estado durable es
  directo: el offset se confirma cuando el método del listener vuelve.
- Escalar horizontalmente es agregar instancias, hasta tener una por partición.

**Negativas y riesgos**

- **Bloqueo de cabeza de cola:** un mensaje lento detiene su partición hasta que termina o se
  agota su presupuesto. Los demás pedidos de esa partición esperan. Es el precio del orden.
- **El throughput tiene techo por partición.** Con 3 particiones hay como máximo 3 mensajes en
  proceso a la vez en todo el grupo de consumidores. Si las APIs están lentas, el lag crece; se
  ve en `kafka_consumer_fetch_manager_records_lag_max` (O8).
- **El MDC no viaja solo a los hilos del enriquecimiento:** se copia a mano en
  `withCurrentMdc` (O3). Una llamada nueva que no use ese envoltorio pierde el `orderId` en sus
  logs.
- **Una llamada cancelada puede seguir viva un instante:** `cancel(true)` interrumpe el virtual
  thread, pero la petición HTTP en vuelo termina o expira por su propio timeout. No afecta el
  resultado, porque el pedido ya se marcó como fallido.
- **Pinning en Java 21:** un virtual thread que bloquea dentro de un bloque `synchronized`
  retiene su hilo de plataforma (JEP 491 lo corrige en Java 24). No se midió si alguna librería
  usada lo provoca en este flujo; con la concurrencia actual no tiene efecto práctico, pero
  importa si se sube mucho.

## Cuándo revisarla

- **El lag crece de forma sostenida con las APIs sanas:** primero, más particiones y más
  instancias. Si no alcanza, paralelismo por clave dentro de la partición.
- **Un mercado o distribuidor concentra el tráfico en una partición** (hot partition, §9):
  revisar la clave o el paralelismo por clave.
- **La latencia de las APIs pasa a ser rutinariamente alta:** caché de productos (§9) antes de
  cambiar el modelo de concurrencia.
- **Se sube la concurrencia a cientos de hilos o se ven bloqueos inexplicables:** medir el
  pinning (`-Djdk.tracePinnedThreads`) o pasar a Java 24 o superior.

## Cómo se verifica

- `ProcessOrderUseCaseTest.callsTheDependenciesConcurrently`: el cliente y los productos se
  llaman en paralelo.
- `ProcessOrderUseCaseTest.exceedingTheEnrichmentBudgetIsATransientFailureAndCancelsTheCalls`:
  al agotar el presupuesto, el pedido queda como fallo transitorio y las llamadas pendientes se
  cancelan.
- `ProcessOrderUseCaseTest.theParallelCallsSeeTheLogContextOfTheMessage`: los hilos del
  enriquecimiento heredan el MDC del mensaje.
- `MongoOrderResultStoreIT.tenConcurrentWritesOfTheSameEventApplyExactlyOnce` y
  `twoConcurrentEventsWithTheSameVersionLeaveOneWinnerAndOneConflict`: la corrección no depende
  del orden de Kafka.
- No hay un test que verifique el procesamiento en serie dentro de una partición. Esa garantía
  es de Spring Kafka con `AckMode.RECORD`, y la configuración está en `KafkaConfig`.
