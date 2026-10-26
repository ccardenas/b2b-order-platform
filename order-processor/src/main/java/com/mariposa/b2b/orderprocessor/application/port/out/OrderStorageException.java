package com.mariposa.b2b.orderprocessor.application.port.out;

/** La persistencia fallo (categoria PERSISTENCE): se reintenta a nivel de mensaje, no aqui. */
public class OrderStorageException extends RuntimeException {

    public OrderStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
