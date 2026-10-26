package com.mariposa.b2b.orderprocessor.infrastructure.http;

import java.time.Duration;

/** Existe para que los tests de reintentos no duerman de verdad y puedan verificar las esperas. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration);

    void sleep(Duration duration) throws InterruptedException;
}
