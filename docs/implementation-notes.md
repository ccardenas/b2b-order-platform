# Notas de implementación

Diferencias reales respecto de `docs/architecture-proposal.md` y decisiones
que la propuesta no fijaba. La propuesta no se reescribe: se anota aquí.

## products-api (hito 2)

| # | Tema | Propuesta | Implementación | Motivo |
|---|---|---|---|---|
| P1 | Códigos HTTP del contrato | El OpenAPI de `GET /products/{productId}` declaraba 200/404/429/500 | Se agregaron **400** (`INVALID_MARKET`) y **503** (`SERVICE_UNAVAILABLE`) a `contracts/http/products-api.openapi.yaml` | La validación de `market` (§4.2 `ErrInvalidMarket`, §10 test de 400) y el fallo semilla `PRD-FAIL-503` no tenían respuesta declarada. Cambio aditivo y compatible. Aprobado por Claudio el 2026-10-25. |
| P2 | IDs de fallo | Ejemplos: `PRD-FAIL-503`, `PRD-SLOW` | Además `PRD-FAIL-500`, `PRD-FAIL-429` (con `Retry-After: 1`) y `PRD-BAD-ENUM` (200 con `status: "ARCHIVED"`) | Cubren las filas de §5.2 que order-processor debe clasificar en el hito 5: 429 y 5xx transitorios, enum desconocido como `EXTERNAL_PERMANENT` (S10). |
| P3 | `PRD-SLOW` | "timeout" | Espera 5 s (más que el read timeout de 2 s de §5.3) y luego sigue al handler real, que responde 404 porque el ID no está en la semilla. Si el cliente corta antes, la espera termina. | Un fallo inyectado no debe tener datos de catálogo propios. Con el timeout de §5.3 el cliente nunca ve el 404. |
| P4 | Request cancelado o vencido | No especificado | El handler responde 503 `SERVICE_UNAVAILABLE`. En el access log, si no se escribió respuesta y el contexto terminó, se registra `status: 499`. | Si el cliente se fue, la respuesta se descarta. Si fue un deadline del servidor, 503 se clasifica como transitorio. El 499 evita que un timeout aparezca como 200 en los logs. |
| P5 | `market` en minúsculas | No especificado | `mx` se rechaza con 400 | El contrato define el enum en mayúsculas. Normalizar ocultaría un error del llamador. |
| P6 | Rutas desconocidas | No especificado | 404 `ROUTE_NOT_FOUND` con el contrato de error, también para un método equivocado (no 405) | Toda respuesta de error respeta `{"error":{...}}`. Con el catch-all `/` de `net/http`, el mux no distingue 404 de 405, y no aporta valor a un consumidor interno. |
| P7 | `traceId` | No especificado de dónde sale | Se toma del header `X-Trace-Id` si viene. Si no, se genera (16 bytes aleatorios en hex) y se devuelve en el mismo header. | Permite que order-processor propague su traza más adelante sin cambiar la API. |
| P8 | Validación contra OpenAPI (§10) | "Respuestas validadas contra OpenAPI" | Los tests decodifican las respuestas con structs estrictos (`DisallowUnknownFields`). **No** hay validación automática contra el YAML. | Validar el YAML desde Go requiere una dependencia externa y `products-api` usa solo la librería estándar. Los fixtures de `contracts/` cubren la forma. Queda como deuda. |
