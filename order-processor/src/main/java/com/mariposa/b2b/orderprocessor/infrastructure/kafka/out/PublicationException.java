package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

/** El broker no confirmo la publicacion (categoria PUBLICATION): el outbox queda PENDING. */
public class PublicationException extends RuntimeException {

    public PublicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
