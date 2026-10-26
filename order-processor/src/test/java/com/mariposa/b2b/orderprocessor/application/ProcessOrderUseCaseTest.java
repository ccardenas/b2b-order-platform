package com.mariposa.b2b.orderprocessor.application;

import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderResult;
import com.mariposa.b2b.orderprocessor.application.port.out.ClientCatalog;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import com.mariposa.b2b.orderprocessor.application.port.out.IdGenerator;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderMetrics;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore.WriteResult;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.application.port.out.ProductCatalog;
import com.mariposa.b2b.orderprocessor.domain.idempotency.RevisionClassifier;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final ProcessOrderCommand.Source SOURCE = new ProcessOrderCommand.Source("orders.created.v1", 2, 1842L);

    private static final ClientSnapshot WHOLESALE_CLIENT =
            new ClientSnapshot("CLI-1", ClientStatus.ACTIVE, ClientSegment.WHOLESALE, TaxRegime.GENERAL, Market.MX);
    private static final ProductSnapshot BEVERAGE =
            new ProductSnapshot("PRD-001", "BEB-600-PET", ProductStatus.ACTIVE, TaxCategory.STANDARD);
    private static final ProductSnapshot SNACK =
            new ProductSnapshot("PRD-002", "SNK-100-BAG", ProductStatus.ACTIVE, TaxCategory.REDUCED);

    private final FakeClientCatalog clients = new FakeClientCatalog();
    private final FakeProductCatalog products = new FakeProductCatalog();
    private final InMemoryOrderResultStore store = new InMemoryOrderResultStore();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final IdGenerator ids = new SequentialIds();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private ExecutorService executor;
    private ProcessOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        useCase = useCaseWithTimeout(Duration.ofSeconds(5));
        clients.willReturn("CLI-1", WHOLESALE_CLIENT);
        products.willReturn(BEVERAGE);
        products.willReturn(SNACK);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        MDC.clear();
    }

    private ProcessOrderUseCase useCaseWithTimeout(Duration timeout) {
        return new ProcessOrderUseCase(clients, products, store, publisher, ids, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC), executor, timeout);
    }

    private static ProcessOrderCommand command(String eventId, int version, OrderItem... items) {
        return new ProcessOrderCommand(
                new IncomingOrder(eventId, version, "ORD-MX-000147", "MX", "MXN", "CLI-1", List.of(items)), SOURCE);
    }

    private static ProcessOrderCommand command(OrderItem... items) {
        return command("EVT-1", 1, items);
    }

    private static OrderItem item(String productId, int quantity, String unitPrice) {
        return new OrderItem(productId, quantity, new BigDecimal(unitPrice));
    }

    private static ExternalCallFailedException transientFailure(String service) {
        return new ExternalCallFailedException(Kind.TRANSIENT, service + ": HTTP 503 after 3 attempt(s)", 3);
    }

    private static ExternalCallFailedException permanentFailure(String service) {
        return new ExternalCallFailedException(Kind.PERMANENT, service + ": invalid response: unknown value for status", 1);
    }

    private ProcessedOrder saved() {
        assertThat(store.writes).hasSize(1);
        return store.writes.get(0);
    }

    // ---------------------------------------------------------------- resultados de negocio

    @Test
    void approvesPricesPersistsAndPublishesAnEligibleOrder() {
        ProcessOrderResult result = useCase.process(command(item("PRD-001", 24, "35.50")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
        ProcessedOrder processed = saved();
        ProcessingOutcome.Approved approved = (ProcessingOutcome.Approved) processed.outcome();
        // Mayorista con 24 unidades en la linea: 3% de descuento. Es el ejemplo de la propuesta §7.1.
        assertThat(approved.lines()).singleElement().satisfies(line -> {
            assertThat(line.grossSubtotal()).isEqualByComparingTo("852.00");
            assertThat(line.discount()).isEqualByComparingTo("25.56");
            assertThat(line.netSubtotal()).isEqualByComparingTo("826.44");
            assertThat(line.taxAmount()).isEqualByComparingTo("132.23");
            assertThat(line.lineTotal()).isEqualByComparingTo("958.67");
        });
        assertThat(approved.totals().grandTotal()).isEqualByComparingTo("958.67");
        assertThat(approved.client()).isEqualTo(WHOLESALE_CLIENT);
        assertThat(processed.source()).isEqualTo(SOURCE);
        assertThat(processed.receivedAt()).isEqualTo(NOW);
        assertThat(processed.processedAt()).isEqualTo(NOW);
    }

    @Test
    void totalsAreTheSumOfTheAlreadyRoundedLines() {
        useCase.process(command(item("PRD-001", 24, "35.50"), item("PRD-002", 3, "10.005")));

        ProcessingOutcome.Approved approved = (ProcessingOutcome.Approved) saved().outcome();
        BigDecimal sumOfLines = approved.lines().stream()
                .map(line -> line.lineTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(approved.totals().grandTotal()).isEqualByComparingTo(sumOfLines);
    }

    @Test
    void publishesRightAfterPersistingWithTheStableOutboxEventIdAndMarksItPublished() {
        useCase.process(command(item("PRD-001", 24, "35.50")));

        assertThat(saved().outboxEventId()).contains("OUT-1");
        assertThat(publisher.published).singleElement().isSameAs(saved());
        assertThat(store.markedPublished).containsExactly("ORD-MX-000147/OUT-1");
        assertThat(store.pendingOutbox()).isEmpty();
    }

    @Test
    void rejectsWhenTheClientDoesNotExistAndStillPublishes() {
        clients.willReturnNothing("CLI-1");

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.REJECTED));
        ProcessingOutcome.Rejected rejected = (ProcessingOutcome.Rejected) saved().outcome();
        assertThat(rejected.reasons()).containsExactly(RejectionReason.CLIENT_NOT_FOUND);
        assertThat(rejected.client()).isEmpty();
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void collectsEveryRejectionReasonInDeterministicOrder() {
        clients.willReturn("CLI-1", new ClientSnapshot("CLI-1", ClientStatus.BLOCKED, ClientSegment.RETAIL, TaxRegime.GENERAL, Market.CO));
        products.willReturn(new ProductSnapshot("PRD-001", "BEB-600-PET", ProductStatus.DISCONTINUED, TaxCategory.STANDARD));
        products.willReturnNothing("PRD-002");

        useCase.process(command(item("PRD-001", 1, "10.00"), item("PRD-002", 1, "10.00")));

        ProcessingOutcome.Rejected rejected = (ProcessingOutcome.Rejected) saved().outcome();
        assertThat(rejected.reasons()).containsExactly(
                RejectionReason.CLIENT_BLOCKED, RejectionReason.CLIENT_MARKET_MISMATCH,
                RejectionReason.PRODUCT_DISCONTINUED, RejectionReason.PRODUCT_NOT_FOUND);
        assertThat(rejected.client()).isPresent();
    }

    @Test
    void asksForProductsInTheMarketOfTheOrder() {
        useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(products.marketsRequested).containsExactly(Market.MX);
    }

    // ---------------------------------------------------------------- fallos tecnicos

    @Test
    void aTransientFailureOfOneProductPersistsATechnicalFailureWithoutPartialCalculation() {
        products.willFail("PRD-002", transientFailure("products-api"));

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 24, "35.50"), item("PRD-002", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Failed(
                ErrorCategory.EXTERNAL_TRANSIENT, "products-api: HTTP 503 after 3 attempt(s)", true));
        ProcessingOutcome.TechnicalFailure failure = (ProcessingOutcome.TechnicalFailure) saved().outcome();
        assertThat(failure.category()).isEqualTo(ErrorCategory.EXTERNAL_TRANSIENT);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(saved().outboxEventId()).isEmpty();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void aPermanentFailureIsATechnicalFailureToo() {
        clients.willFail("CLI-1", permanentFailure("clients-api"));

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(result).isInstanceOfSatisfying(ProcessOrderResult.Failed.class, failed -> {
            assertThat(failed.category()).isEqualTo(ErrorCategory.EXTERNAL_PERMANENT);
            assertThat(failed.persisted()).isTrue();
        });
        assertThat(saved().outcome().status()).isEqualTo(OrderStatus.TECHNICAL_FAILURE);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void withSeveralFailuresTheClientIsReportedFirstSoTheResultIsDeterministic() {
        clients.willFail("CLI-1", transientFailure("clients-api"));
        products.willFail("PRD-001", permanentFailure("products-api"));

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(result).isInstanceOfSatisfying(ProcessOrderResult.Failed.class,
                failed -> assertThat(failed.category()).isEqualTo(ErrorCategory.EXTERNAL_TRANSIENT));
    }

    @Test
    void aTechnicalFailureWinsOverBusinessRejectionsFoundInTheSameOrder() {
        clients.willReturnNothing("CLI-1");
        products.willFail("PRD-001", transientFailure("products-api"));

        useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(saved().outcome().status()).isEqualTo(OrderStatus.TECHNICAL_FAILURE);
    }

    @Test
    void callsTheDependenciesConcurrently() {
        // Las tres llamadas (cliente + 2 productos) esperan a que lleguen las otras dos:
        // si fueran secuenciales, ninguna pasaria la barrera.
        CountDownLatch allArrived = new CountDownLatch(3);
        Runnable arriveThenWait = () -> {
            allArrived.countDown();
            try {
                if (!allArrived.await(2, TimeUnit.SECONDS)) {
                    throw transientFailure("barrier");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        clients.beforeEachCall = arriveThenWait;
        products.beforeEachCall = arriveThenWait;

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 1, "10.00"), item("PRD-002", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
    }

    @Test
    void exceedingTheEnrichmentBudgetIsATransientFailureAndCancelsTheCalls() throws InterruptedException {
        CountDownLatch cancelled = new CountDownLatch(1);
        clients.beforeEachCall = () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                cancelled.countDown();
            }
        };
        ProcessOrderUseCase tight = useCaseWithTimeout(Duration.ofMillis(150));

        ProcessOrderResult result = tight.process(command(item("PRD-001", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Failed(
                ErrorCategory.EXTERNAL_TRANSIENT, "enrichment exceeded 150 ms", true));
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).as("the slow call was interrupted").isTrue();
    }

    @Test
    void anUnexpectedErrorIsNotDisguisedAsATechnicalFailure() {
        products.willThrow("PRD-001", new IllegalStateException("bug"));

        assertThatThrownBy(() -> useCase.process(command(item("PRD-001", 1, "10.00"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("bug");
        assertThat(store.writes).isEmpty();
    }

    // ---------------------------------------------------------------- validacion

    @Test
    void anInvalidOrderGoesToTheDlqWithoutTouchingStoreOrDependencies() {
        ProcessOrderResult result = useCase.process(command());

        assertThat(result).isEqualTo(new ProcessOrderResult.Failed(ErrorCategory.VALIDATION, "EMPTY_ITEMS", false));
        assertThat(store.writes).isEmpty();
        assertThat(store.findRevisionCalls.get()).isZero();
        assertThat(clients.calls.get() + products.calls.get()).isZero();
    }

    // ---------------------------------------------------------------- idempotencia

    @Test
    void aRepeatedEventIsRecognizedBeforeCallingAnyDependency() {
        ProcessOrderCommand event = command(item("PRD-001", 24, "35.50"));
        useCase.process(event);
        int callsAfterFirst = clients.calls.get() + products.calls.get();

        ProcessOrderResult second = useCase.process(event);

        assertThat(second).isEqualTo(new ProcessOrderResult.Skipped(ErrorCategory.DUPLICATE));
        assertThat(clients.calls.get() + products.calls.get()).isEqualTo(callsAfterFirst);
        assertThat(store.writes).hasSize(1);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void anOlderVersionNeverOverwritesANewerOne() {
        useCase.process(command("EVT-2", 2, item("PRD-001", 24, "35.50")));

        ProcessOrderResult result = useCase.process(command("EVT-1", 1, item("PRD-001", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Skipped(ErrorCategory.STALE));
        assertThat(store.stored("ORD-MX-000147").eventVersion()).isEqualTo(2);
        assertThat(store.writes).hasSize(1);
    }

    @Test
    void aNewerVersionReplacesTheStoredResult() {
        useCase.process(command("EVT-1", 1, item("PRD-001", 1, "10.00")));

        ProcessOrderResult result = useCase.process(command("EVT-2", 2, item("PRD-001", 24, "35.50")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
        assertThat(store.stored("ORD-MX-000147")).isEqualTo(new StoredRevision(2, "EVT-2", OrderStatus.APPROVED));
        assertThat(store.writes).hasSize(2);
    }

    @Test
    void theSameVersionWithAnotherEventIdIsAConflictAndKeepsTheFirstWinner() {
        useCase.process(command("EVT-1", 1, item("PRD-001", 24, "35.50")));

        ProcessOrderResult result = useCase.process(command("EVT-OTHER", 1, item("PRD-001", 1, "10.00")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Failed(
                ErrorCategory.VERSION_CONFLICT, "same eventVersion received with a different eventId", false));
        assertThat(store.stored("ORD-MX-000147").eventId()).isEqualTo("EVT-1");
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void theSameEventIsRetriedAfterATechnicalFailureAndReplacesIt() {
        ProcessOrderCommand event = command(item("PRD-001", 24, "35.50"));
        products.willFail("PRD-001", transientFailure("products-api"));
        useCase.process(event);
        assertThat(store.stored("ORD-MX-000147").status()).isEqualTo(OrderStatus.TECHNICAL_FAILURE);

        products.willReturn(BEVERAGE);
        ProcessOrderResult retry = useCase.process(event);

        assertThat(retry).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
        assertThat(store.stored("ORD-MX-000147").status()).isEqualTo(OrderStatus.APPROVED);
        assertThat(publisher.published).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = WriteResult.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
    void theConditionalWriteIsTheAuthorityWhenARaceHidesFromTheEarlyCheck(WriteResult lostRace) {
        store.forceNextWrite(lostRace);

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 24, "35.50")));

        switch (lostRace) {
            case DUPLICATE -> assertThat(result).isEqualTo(new ProcessOrderResult.Skipped(ErrorCategory.DUPLICATE));
            case STALE -> assertThat(result).isEqualTo(new ProcessOrderResult.Skipped(ErrorCategory.STALE));
            case VERSION_CONFLICT -> assertThat(result).isInstanceOfSatisfying(ProcessOrderResult.Failed.class, failed -> {
                assertThat(failed.category()).isEqualTo(ErrorCategory.VERSION_CONFLICT);
                assertThat(failed.persisted()).isFalse();
            });
            case APPLIED -> throw new AssertionError("excluded");
        }
        assertThat(publisher.published).as("nothing is published when this instance did not write").isEmpty();
    }

    // ---------------------------------------------------------------- metricas y contexto de log

    @Test
    void countsEveryWrittenResultByStatusAndMarket() {
        useCase.process(command(item("PRD-001", 24, "35.50")));

        assertThat(metrics.events).containsExactly("processed:APPROVED:MX");
    }

    @Test
    void countsEachRejectionReason() {
        clients.willReturnNothing("CLI-1");
        products.willReturnNothing("PRD-001");

        useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(metrics.events).containsExactly(
                "processed:REJECTED:MX", "rejected:CLIENT_NOT_FOUND", "rejected:PRODUCT_NOT_FOUND");
    }

    @Test
    void countsTechnicalFailuresAsProcessedToo() {
        products.willFail("PRD-001", transientFailure("products-api"));

        useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(metrics.events).containsExactly("processed:TECHNICAL_FAILURE:MX");
    }

    @Test
    void countsDuplicatesAndStaleWithoutCountingThemAsProcessed() {
        useCase.process(command("EVT-2", 2, item("PRD-001", 1, "10.00")));
        useCase.process(command("EVT-2", 2, item("PRD-001", 1, "10.00")));
        useCase.process(command("EVT-1", 1, item("PRD-001", 1, "10.00")));

        assertThat(metrics.events).containsExactly("processed:APPROVED:MX", "duplicate", "stale");
    }

    @Test
    void aWriteLostToARaceIsCountedLikeTheEarlyCheck() {
        store.forceNextWrite(WriteResult.DUPLICATE);

        useCase.process(command(item("PRD-001", 1, "10.00")));

        assertThat(metrics.events).containsExactly("duplicate");
    }

    @Test
    void theParallelCallsSeeTheLogContextOfTheMessage() {
        MDC.put("orderId", "ORD-MX-000147");
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        clients.beforeEachCall = () -> seen.add("client:" + MDC.get("orderId"));
        products.beforeEachCall = () -> seen.add("product:" + MDC.get("orderId"));

        useCase.process(command(item("PRD-001", 1, "10.00"), item("PRD-002", 1, "10.00")));

        assertThat(seen).containsExactlyInAnyOrder(
                "client:ORD-MX-000147", "product:ORD-MX-000147", "product:ORD-MX-000147");
        assertThat(MDC.get(TransitionLog.OUTCOME)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- publicacion y persistencia

    @Test
    void aPublicationFailureLeavesTheOutboxPendingAndDoesNotFailTheMessage() {
        publisher.failWith = new IllegalStateException("broker down");

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 24, "35.50")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
        assertThat(store.markedPublished).isEmpty();
        assertThat(store.pendingOutbox()).containsExactly("ORD-MX-000147/OUT-1");
    }

    @Test
    void aFailureMarkingPublishedIsAlsoTolerated() {
        store.failMarkPublished = new OrderStorageException("mongo down", new RuntimeException());

        ProcessOrderResult result = useCase.process(command(item("PRD-001", 24, "35.50")));

        assertThat(result).isEqualTo(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void aStorageFailureOnWritePropagatesSoTheMessageIsRetried() {
        store.failSave = new OrderStorageException("mongo down", new RuntimeException());

        assertThatThrownBy(() -> useCase.process(command(item("PRD-001", 24, "35.50"))))
                .isSameAs(store.failSave);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void aStorageFailureOnTheEarlyReadPropagatesWithoutCallingDependencies() {
        store.failFind = new OrderStorageException("mongo down", new RuntimeException());

        assertThatThrownBy(() -> useCase.process(command(item("PRD-001", 24, "35.50"))))
                .isSameAs(store.failFind);
        assertThat(clients.calls.get() + products.calls.get()).isZero();
    }

    // ---------------------------------------------------------------- fakes

    private static final class FakeClientCatalog implements ClientCatalog {
        private final Map<String, Function<String, Optional<ClientSnapshot>>> behavior = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile Runnable beforeEachCall = () -> { };

        void willReturn(String id, ClientSnapshot client) {
            behavior.put(id, key -> Optional.of(client));
        }

        void willReturnNothing(String id) {
            behavior.put(id, key -> Optional.empty());
        }

        void willFail(String id, RuntimeException failure) {
            behavior.put(id, key -> {
                throw failure;
            });
        }

        @Override
        public Optional<ClientSnapshot> findById(String clientId) {
            calls.incrementAndGet();
            beforeEachCall.run();
            return behavior.getOrDefault(clientId, key -> Optional.empty()).apply(clientId);
        }
    }

    private static final class FakeProductCatalog implements ProductCatalog {
        private final Map<String, Function<String, Optional<ProductSnapshot>>> behavior = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<Market> marketsRequested = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile Runnable beforeEachCall = () -> { };

        void willReturn(ProductSnapshot product) {
            behavior.put(product.productId(), key -> Optional.of(product));
        }

        void willReturnNothing(String id) {
            behavior.put(id, key -> Optional.empty());
        }

        void willFail(String id, ExternalCallFailedException failure) {
            behavior.put(id, key -> {
                throw failure;
            });
        }

        void willThrow(String id, RuntimeException failure) {
            behavior.put(id, key -> {
                throw failure;
            });
        }

        @Override
        public Optional<ProductSnapshot> findById(String productId, Market market) {
            calls.incrementAndGet();
            marketsRequested.add(market);
            beforeEachCall.run();
            return behavior.getOrDefault(productId, key -> Optional.empty()).apply(productId);
        }
    }

    /** Reproduce la semantica de la escritura condicional de §6.2 en memoria. */
    private static final class InMemoryOrderResultStore implements OrderResultStore {
        private final RevisionClassifier classifier = new RevisionClassifier();
        private final Map<String, ProcessedOrder> byOrderId = new HashMap<>();
        private final Map<String, Boolean> published = new HashMap<>();
        final List<ProcessedOrder> writes = new ArrayList<>();
        final List<String> markedPublished = new ArrayList<>();
        final AtomicInteger findRevisionCalls = new AtomicInteger();
        private WriteResult forced;
        OrderStorageException failFind;
        OrderStorageException failSave;
        OrderStorageException failMarkPublished;

        void forceNextWrite(WriteResult result) {
            forced = result;
        }

        StoredRevision stored(String orderId) {
            return revisionOf(byOrderId.get(orderId));
        }

        List<String> pendingOutbox() {
            return published.entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).sorted().toList();
        }

        @Override
        public synchronized Optional<StoredRevision> findRevision(String orderId) {
            findRevisionCalls.incrementAndGet();
            if (failFind != null) {
                throw failFind;
            }
            return Optional.ofNullable(byOrderId.get(orderId)).map(InMemoryOrderResultStore::revisionOf);
        }

        @Override
        public synchronized WriteResult save(ProcessedOrder processed) {
            if (failSave != null) {
                throw failSave;
            }
            if (forced != null) {
                WriteResult result = forced;
                forced = null;
                return result;
            }
            String orderId = processed.order().orderId();
            var comparison = classifier.classify(
                    Optional.ofNullable(byOrderId.get(orderId)).map(InMemoryOrderResultStore::revisionOf),
                    processed.order().eventVersion(), processed.order().eventId());
            if (!comparison.isWritable()) {
                return switch (comparison) {
                    case DUPLICATE -> WriteResult.DUPLICATE;
                    case STALE -> WriteResult.STALE;
                    default -> WriteResult.VERSION_CONFLICT;
                };
            }
            byOrderId.put(orderId, processed);
            writes.add(processed);
            processed.outboxEventId().ifPresent(outboxId -> published.put(orderId + "/" + outboxId, false));
            return WriteResult.APPLIED;
        }

        @Override
        public synchronized void markPublished(String orderId, String outboxEventId, Instant publishedAt) {
            if (failMarkPublished != null) {
                throw failMarkPublished;
            }
            markedPublished.add(orderId + "/" + outboxEventId);
            published.put(orderId + "/" + outboxEventId, true);
        }

        private static StoredRevision revisionOf(ProcessedOrder processed) {
            return new StoredRevision(processed.order().eventVersion(), processed.order().eventId(), processed.outcome().status());
        }
    }

    private static final class RecordingPublisher implements ProcessedEventPublisher {
        final List<ProcessedOrder> published = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void publish(ProcessedOrder processed) {
            if (failWith != null) {
                throw failWith;
            }
            published.add(processed);
        }
    }

    private static final class RecordingMetrics implements OrderMetrics {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void processed(OrderStatus status, Market market) {
            events.add("processed:" + status + ":" + market);
        }

        @Override
        public void rejected(RejectionReason reason) {
            events.add("rejected:" + reason);
        }

        @Override
        public void duplicate() {
            events.add("duplicate");
        }

        @Override
        public void stale() {
            events.add("stale");
        }
    }

    private static final class SequentialIds implements IdGenerator {
        private final AtomicInteger next = new AtomicInteger(1);

        @Override
        public String newId() {
            return "OUT-" + next.getAndIncrement();
        }
    }
}
