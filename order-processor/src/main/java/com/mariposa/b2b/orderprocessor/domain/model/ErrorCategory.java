package com.mariposa.b2b.orderprocessor.domain.model;

/**
 * Categorias de la tabla de la propuesta §5.2. Son tambien el valor del
 * header x-error-category de la DLT (§5.5), por eso los nombres son estables.
 * DUPLICATE y STALE no son errores: se listan porque la tabla los clasifica
 * junto al resto de situaciones que terminan un mensaje sin resultado nuevo.
 * UNEXPECTED no esta en la tabla: es un error no previsto (un bug), que se
 * reintenta a nivel de mensaje como PERSISTENCE y, si persiste, va a la DLT.
 */
public enum ErrorCategory {
    DESERIALIZATION,
    VALIDATION,
    EXTERNAL_TRANSIENT,
    EXTERNAL_PERMANENT,
    PERSISTENCE,
    PUBLICATION,
    DUPLICATE,
    STALE,
    VERSION_CONFLICT,
    UNEXPECTED
}
