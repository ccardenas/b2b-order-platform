package com.mariposa.b2b.orderprocessor.infrastructure.mongo;

import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.OrderTotals;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mapeo explicito de {@link ProcessedOrder} al documento de la coleccion orders
 * (forma de la propuesta §7.1). El documento Mongo es este layout BSON: no hay
 * POJO intermedio ni mapeo por reflexion. Importes y tasas van como Decimal128,
 * nunca como double; los instantes, como BSON Date (precision de milisegundos).
 */
public final class OrderDocumentMapper {

    static final int SCHEMA_VERSION = 1;

    static final String ID = "_id";
    static final String EVENT_ID = "eventId";
    static final String EVENT_VERSION = "eventVersion";
    static final String STATUS = "status";
    static final String PROCESSED_AT = "processedAt";
    static final String FIRST_RECEIVED_AT = "firstReceivedAt";
    static final String OUTBOX = "outbox";
    static final String OUTBOX_EVENT_ID = "outbox.eventId";
    static final String OUTBOX_STATUS = "outbox.status";
    static final String OUTBOX_CREATED_AT = "outbox.createdAt";
    static final String OUTBOX_PUBLISHED_AT = "outbox.publishedAt";
    static final String OUTBOX_PUBLISH_ATTEMPTS = "outbox.publishAttempts";

    static final String OUTBOX_PENDING = "PENDING";
    static final String OUTBOX_PUBLISHED = "PUBLISHED";

    private OrderDocumentMapper() {
    }

    /**
     * Campos del $set de la escritura condicional. No incluye _id (sale del filtro),
     * firstReceivedAt (va en $setOnInsert) ni outbox cuando el resultado es un fallo
     * tecnico: en ese caso el update lo quita con $unset (ver {@link #hasOutbox}).
     */
    static Document fieldsToSet(ProcessedOrder processed) {
        var order = processed.order();
        ProcessingOutcome outcome = processed.outcome();

        Document fields = new Document()
                .append("schemaVersion", SCHEMA_VERSION)
                .append(EVENT_ID, order.eventId())
                .append(EVENT_VERSION, order.eventVersion())
                .append(STATUS, outcome.status().name())
                .append("market", order.market().name())
                .append("currency", order.currency().name())
                .append("clientId", order.clientId())
                .append("client", client(outcome))
                .append("lines", lines(outcome))
                .append("totals", outcome instanceof ProcessingOutcome.Approved approved ? totals(approved.totals()) : null)
                .append("rejectionReasons", reasons(outcome).stream().map(Enum::name).toList())
                .append("technicalError", technicalError(outcome))
                .append("receivedAt", date(processed.receivedAt()))
                .append(PROCESSED_AT, date(processed.processedAt()))
                .append("trace", new Document()
                        .append("sourceTopic", processed.source().topic())
                        .append("partition", processed.source().partition())
                        .append("offset", processed.source().offset()));

        processed.outboxEventId().ifPresent(outboxEventId -> fields.append(OUTBOX, new Document()
                .append("eventId", outboxEventId)
                .append("status", OUTBOX_PENDING)
                .append("createdAt", date(processed.processedAt()))
                .append("publishedAt", null)
                .append("publishAttempts", 0)
                .append("payload", payload(processed))));
        return fields;
    }

    static boolean hasOutbox(ProcessedOrder processed) {
        return processed.outboxEventId().isPresent();
    }

    /**
     * Evento orders.processed.v1 tal como se publicara (§7.3: "el payload publicado se
     * lee del documento persistido"). Sigue contracts/events/orders.processed.v1.schema.json:
     * totals es null y reason es la primera razon cuando el pedido fue rechazado.
     * Es publico porque la publicacion inmediata (kafka/out) usa exactamente este
     * mismo payload: asi el camino rapido y el relay publican los mismos bytes.
     */
    public static Document payload(ProcessedOrder processed) {
        String outboxEventId = processed.outboxEventId()
                .orElseThrow(() -> new IllegalArgumentException("a TECHNICAL_FAILURE has no processed event"));
        var order = processed.order();
        ProcessingOutcome outcome = processed.outcome();
        List<RejectionReason> reasons = reasons(outcome);
        return new Document()
                .append("eventId", outboxEventId)
                .append("eventVersion", order.eventVersion())
                .append("occurredAt", date(processed.processedAt()))
                .append("sourceEventId", order.eventId())
                .append("orderId", order.orderId())
                .append("status", outcome.status().name())
                .append("market", order.market().name())
                .append("currency", order.currency().name())
                .append("totals", outcome instanceof ProcessingOutcome.Approved approved ? totals(approved.totals()) : null)
                .append("reason", reasons.isEmpty() ? null : reasons.get(0).name());
    }

    private static Document client(ProcessingOutcome outcome) {
        ClientSnapshot client = switch (outcome) {
            case ProcessingOutcome.Approved approved -> approved.client();
            case ProcessingOutcome.Rejected rejected -> rejected.client().orElse(null);
            case ProcessingOutcome.TechnicalFailure failure -> null;
        };
        if (client == null) {
            return null;
        }
        return new Document()
                .append("clientId", client.clientId())
                .append("status", client.status().name())
                .append("segment", client.segment().name())
                .append("taxRegime", client.taxRegime().name())
                .append("market", client.market().name());
    }

    private static List<Document> lines(ProcessingOutcome outcome) {
        if (!(outcome instanceof ProcessingOutcome.Approved approved)) {
            return List.of();
        }
        return approved.lines().stream().map(OrderDocumentMapper::line).toList();
    }

    private static Document line(PricedOrderLine line) {
        return new Document()
                .append("productId", line.productId())
                .append("sku", line.sku())
                .append("taxCategory", line.taxCategory().name())
                .append("quantity", line.quantity())
                .append("unitPrice", decimal(line.unitPrice()))
                .append("grossSubtotal", decimal(line.grossSubtotal()))
                .append("discountRate", decimal(line.discountRate()))
                .append("discount", decimal(line.discount()))
                .append("netSubtotal", decimal(line.netSubtotal()))
                .append("taxRate", decimal(line.taxRate()))
                .append("taxAmount", decimal(line.taxAmount()))
                .append("lineTotal", decimal(line.lineTotal()));
    }

    private static Document totals(OrderTotals totals) {
        return new Document()
                .append("grossSubtotal", decimal(totals.grossSubtotal()))
                .append("discount", decimal(totals.discount()))
                .append("netSubtotal", decimal(totals.netSubtotal()))
                .append("tax", decimal(totals.tax()))
                .append("grandTotal", decimal(totals.grandTotal()));
    }

    private static List<RejectionReason> reasons(ProcessingOutcome outcome) {
        return outcome instanceof ProcessingOutcome.Rejected rejected ? rejected.reasons() : List.of();
    }

    private static Document technicalError(ProcessingOutcome outcome) {
        if (!(outcome instanceof ProcessingOutcome.TechnicalFailure failure)) {
            return null;
        }
        return new Document()
                .append("category", failure.category().name())
                .append("cause", failure.cause())
                .append("attempts", failure.attempts());
    }

    private static Decimal128 decimal(BigDecimal value) {
        return new Decimal128(value);
    }

    static Date date(Instant instant) {
        return Date.from(instant);
    }
}
