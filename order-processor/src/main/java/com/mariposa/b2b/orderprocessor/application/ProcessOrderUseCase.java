package com.mariposa.b2b.orderprocessor.application;

import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderResult;
import com.mariposa.b2b.orderprocessor.application.port.out.ClientCatalog;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.IdGenerator;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderMetrics;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.application.port.out.ProductCatalog;
import com.mariposa.b2b.orderprocessor.domain.eligibility.EligibilityPolicy;
import com.mariposa.b2b.orderprocessor.domain.idempotency.RevisionClassifier;
import com.mariposa.b2b.orderprocessor.domain.idempotency.RevisionComparison;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.OrderTotals;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;
import com.mariposa.b2b.orderprocessor.domain.pricing.DiscountPolicy;
import com.mariposa.b2b.orderprocessor.domain.pricing.LinePricer;
import com.mariposa.b2b.orderprocessor.domain.pricing.TaxRateTable;
import com.mariposa.b2b.orderprocessor.domain.validation.OrderValidator;
import com.mariposa.b2b.orderprocessor.domain.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orquesta el flujo de la propuesta §5.1: validar, chequeo temprano de
 * idempotencia, enriquecer, evaluar, escritura condicional y publicacion
 * inmediata. No decide nada de Kafka: devuelve un {@link ProcessOrderResult}
 * y el listener (hito 7) traduce eso a DLT y commit de offset.
 *
 * Las excepciones que escapan de aqui (OrderStorageException, errores
 * inesperados) son a proposito: significan "no hay estado durable", y el
 * manejador de errores del listener reintenta el mensaje completo (§5.3).
 */
public final class ProcessOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderUseCase.class);

    private final ClientCatalog clients;
    private final ProductCatalog products;
    private final OrderResultStore store;
    private final ProcessedEventPublisher publisher;
    private final IdGenerator ids;
    private final OrderMetrics metrics;
    private final Clock clock;
    private final ExecutorService enrichmentExecutor;
    private final Duration enrichmentTimeout;

    // Servicios de dominio sin estado ni dependencias: no hay nada que inyectar.
    private final OrderValidator validator = new OrderValidator();
    private final RevisionClassifier revisions = new RevisionClassifier();
    private final EligibilityPolicy eligibility = new EligibilityPolicy();
    private final LinePricer pricer = new LinePricer(new TaxRateTable(), new DiscountPolicy());

    public ProcessOrderUseCase(
            ClientCatalog clients,
            ProductCatalog products,
            OrderResultStore store,
            ProcessedEventPublisher publisher,
            IdGenerator ids,
            OrderMetrics metrics,
            Clock clock,
            ExecutorService enrichmentExecutor,
            Duration enrichmentTimeout
    ) {
        this.clients = clients;
        this.products = products;
        this.store = store;
        this.publisher = publisher;
        this.ids = ids;
        this.metrics = metrics;
        this.clock = clock;
        this.enrichmentExecutor = enrichmentExecutor;
        this.enrichmentTimeout = enrichmentTimeout;
    }

    public ProcessOrderResult process(ProcessOrderCommand command) {
        Instant receivedAt = clock.instant();
        return switch (validator.validate(command.order())) {
            case ValidationResult.Invalid invalid -> {
                TransitionLog.warn(log, "validated", "valid", false, "failure", invalid.failure());
                yield new ProcessOrderResult.Failed(ErrorCategory.VALIDATION, invalid.failure().name(), false);
            }
            case ValidationResult.Valid valid -> {
                TransitionLog.info(log, "validated", "valid", true, "items", valid.order().items().size());
                yield processValid(valid.order(), command, receivedAt);
            }
        };
    }

    private ProcessOrderResult processValid(ValidatedOrder order, ProcessOrderCommand command, Instant receivedAt) {
        // Optimizacion (§5.1 paso 3): evita llamar a las APIs para un evento ya resuelto.
        // La garantia real es la escritura condicional de mas abajo.
        RevisionComparison early = revisions.classify(
                store.findRevision(order.orderId()), order.eventVersion(), order.eventId());
        if (!early.isWritable()) {
            return closeWithoutWrite(early);
        }

        ProcessingOutcome outcome = evaluate(order);
        MDC.put(TransitionLog.OUTCOME, outcome.status().name());
        TransitionLog.info(log, "evaluated", "status", outcome.status(), "reasons", reasons(outcome));
        Instant processedAt = clock.instant();
        Optional<String> outboxEventId = outcome instanceof ProcessingOutcome.TechnicalFailure
                ? Optional.empty()
                : Optional.of(ids.newId());
        ProcessedOrder processed = new ProcessedOrder(
                order, outcome, command.source(), receivedAt, processedAt, outboxEventId);

        return switch (store.save(processed)) {
            case APPLIED -> {
                recordProcessed(processed);
                yield afterWrite(processed);
            }
            case DUPLICATE -> closeWithoutWrite(RevisionComparison.DUPLICATE);
            case STALE -> closeWithoutWrite(RevisionComparison.STALE);
            case VERSION_CONFLICT -> closeWithoutWrite(RevisionComparison.VERSION_CONFLICT);
        };
    }

    /**
     * DUPLICATE y STALE terminan aqui (son transiciones propias del anexo). VERSION_CONFLICT
     * no se loguea aqui: termina en la DLT y la linea "dlt" ya lleva la categoria.
     */
    private ProcessOrderResult closeWithoutWrite(RevisionComparison comparison) {
        return switch (comparison) {
            case DUPLICATE -> {
                metrics.duplicate();
                TransitionLog.info(log, "duplicate");
                yield new ProcessOrderResult.Skipped(ErrorCategory.DUPLICATE);
            }
            case STALE -> {
                metrics.stale();
                TransitionLog.info(log, "stale");
                yield new ProcessOrderResult.Skipped(ErrorCategory.STALE);
            }
            case VERSION_CONFLICT -> new ProcessOrderResult.Failed(
                    ErrorCategory.VERSION_CONFLICT, "same eventVersion received with a different eventId", false);
            case NEWER, RETRY_OF_TECHNICAL_FAILURE -> throw new IllegalStateException("writable: " + comparison);
        };
    }

    private ProcessOrderResult afterWrite(ProcessedOrder processed) {
        ProcessingOutcome outcome = processed.outcome();
        if (outcome instanceof ProcessingOutcome.TechnicalFailure failure) {
            TransitionLog.warn(log, "persisted", "status", outcome.status(), "category", failure.category());
            return new ProcessOrderResult.Failed(failure.category(), failure.cause(), true);
        }
        TransitionLog.info(log, "persisted", "status", outcome.status(),
                "outboxEventId", processed.outboxEventId().orElseThrow());
        publishNow(processed);
        return new ProcessOrderResult.Completed(outcome.status());
    }

    /**
     * Camino rapido del outbox (§7.2). Un fallo aqui no es un fallo del mensaje:
     * el resultado ya es durable y el outbox sigue PENDING, asi que el relay
     * lo publica despues con el mismo eventId.
     */
    private void publishNow(ProcessedOrder processed) {
        String outboxEventId = processed.outboxEventId().orElseThrow();
        try {
            publisher.publish(processed);
            store.markPublished(processed.order().orderId(), outboxEventId, clock.instant());
            TransitionLog.info(log, "published", "outboxEventId", outboxEventId, "path", "immediate");
        } catch (RuntimeException e) {
            // No es una transicion del anexo: el pedido sigue en "persisted" y el relay lo publica.
            TransitionLog.warn(log, "publication_pending", "category", ErrorCategory.PUBLICATION,
                    "outboxEventId", outboxEventId, "error", e.getClass().getSimpleName());
        }
    }

    private ProcessingOutcome evaluate(ValidatedOrder order) {
        Enrichment enrichment;
        try {
            enrichment = enrich(order);
        } catch (ExternalCallFailedException e) {
            // Nunca se calcula con datos parciales (§5.4): un solo fallo invalida el pedido entero.
            TransitionLog.warn(log, "enriched", "ok", false, "category", e.category(), "cause", e.getMessage());
            return new ProcessingOutcome.TechnicalFailure(e.category(), e.getMessage(), e.attempts());
        }
        TransitionLog.info(log, "enriched", "ok", true, "clientFound", enrichment.client().isPresent(),
                "productsFound", enrichment.products().size());

        List<RejectionReason> reasons = eligibility.evaluate(order, enrichment.client(), enrichment.products());
        if (!reasons.isEmpty()) {
            return new ProcessingOutcome.Rejected(enrichment.client(), reasons);
        }

        ClientSnapshot client = enrichment.client().orElseThrow();
        List<PricedOrderLine> lines = order.items().stream()
                .map(item -> pricer.price(item, enrichment.products().get(item.productId()), order.market(), client))
                .toList();
        return new ProcessingOutcome.Approved(client, lines, OrderTotals.aggregate(lines));
    }

    /**
     * Cliente y todos los productos en paralelo (virtual threads en produccion).
     * Se necesitan todos aunque el cliente falte: la elegibilidad junta TODAS las
     * razones de rechazo (S9). Se espera en orden fijo (cliente, luego items), asi
     * que si hay varios fallos gana siempre el mismo y el resultado es determinista.
     */
    private Enrichment enrich(ValidatedOrder order) {
        Future<Optional<ClientSnapshot>> clientCall =
                enrichmentExecutor.submit(withCurrentMdc(() -> clients.findById(order.clientId())));
        Map<String, Future<Optional<ProductSnapshot>>> productCalls = new LinkedHashMap<>();
        for (OrderItem item : order.items()) {
            productCalls.put(item.productId(),
                    enrichmentExecutor.submit(withCurrentMdc(() -> products.findById(item.productId(), order.market()))));
        }

        long deadline = System.nanoTime() + enrichmentTimeout.toNanos();
        try {
            Optional<ClientSnapshot> client = await(clientCall, deadline);
            Map<String, ProductSnapshot> found = new HashMap<>();
            for (Map.Entry<String, Future<Optional<ProductSnapshot>>> call : productCalls.entrySet()) {
                await(call.getValue(), deadline).ifPresent(product -> found.put(call.getKey(), product));
            }
            return new Enrichment(client, found);
        } finally {
            // Si salimos por fallo o timeout, no dejar llamadas huerfanas. En las terminadas no hace nada.
            clientCall.cancel(true);
            productCalls.values().forEach(call -> call.cancel(true));
        }
    }

    private <T> T await(Future<T> call, long deadlineNanos) {
        try {
            return call.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Los adaptadores ya acotan cada llamada; esto es la red de seguridad del presupuesto total (§5.3).
            throw new ExternalCallFailedException(ExternalCallFailedException.Kind.TRANSIENT,
                    "enrichment exceeded " + enrichmentTimeout.toMillis() + " ms", 1);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ExternalCallFailedException failure) {
                throw failure;
            }
            if (e.getCause() instanceof RuntimeException unexpected) {
                throw unexpected;
            }
            throw new IllegalStateException("unexpected failure while enriching order", e.getCause());
        } catch (InterruptedException e) {
            // Apagado del listener: no es un fallo del pedido, no debe persistirse como TECHNICAL_FAILURE.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while enriching order", e);
        }
    }

    /**
     * El MDC es por hilo: sin esto, los logs de reintentos HTTP de las llamadas en paralelo
     * saldrian sin orderId. Cada tarea corre en un virtual thread nuevo, pero se limpia igual.
     */
    private static <T> Callable<T> withCurrentMdc(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }

    private void recordProcessed(ProcessedOrder processed) {
        metrics.processed(processed.outcome().status(), processed.order().market());
        if (processed.outcome() instanceof ProcessingOutcome.Rejected rejected) {
            rejected.reasons().forEach(metrics::rejected);
        }
    }

    private static List<RejectionReason> reasons(ProcessingOutcome outcome) {
        return outcome instanceof ProcessingOutcome.Rejected rejected ? rejected.reasons() : List.of();
    }

    private record Enrichment(Optional<ClientSnapshot> client, Map<String, ProductSnapshot> products) {
    }
}
