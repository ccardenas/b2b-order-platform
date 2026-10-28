# Registro de uso de IA

Propuestas de la IA que se rechazaron o corrigieron durante el desarrollo. El resumen para el
evaluador está en la sección "Uso de herramientas de IA" del [README](../README.md).

| Fecha | Qué se propuso | Por qué se descartó | Qué se hizo en su lugar |
|---|---|---|---|
| 2026-10-25 | Claude Code propuso un fallo inyectado `CLI-FAIL-503` en clients-api, simétrico a `PRD-FAIL-503` de products-api. | El OpenAPI de clients-api no declara 503: había que ampliar el contrato solo para un fallo de prueba. El caso transitorio ya lo cubren 500 y 429. | `CLI-FAIL-500` y `CLI-FAIL-429` (con `Retry-After: 1`), sin cambios al contrato. Nota C2 de `implementation-notes.md`. |
