package com.mariposa.b2b.orderprocessor.infrastructure.mongo;

import com.mariposa.b2b.orderprocessor.MongoTestContainer;
import com.mariposa.b2b.orderprocessor.application.ProcessOrderUseCase;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderResult;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore.WriteResult;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Currency;
import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.OrderTotals;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;
import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escritura condicional (§6.2) y los tres casos del enunciado (§6.3) contra un
 * MongoDB real. Los tests que llegan a save() sin pasar por el caso de uso
 * ejercitan a proposito el camino del E11000: el chequeo temprano no esta.
 */
class MongoOrderResultStoreIT {

    private static final String ORDER_ID = "ORD-MX-000147";
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final ProcessOrderCommand.Source SOURCE = new ProcessOrderCommand.Source("orders.created.v1", 1, 42L);
    private static final ClientSnapshot CLIENT =
            new ClientSnapshot("CLI-1", ClientStatus.ACTIVE, ClientSegment.WHOLESALE, TaxRegime.GENERAL, Market.MX);
    private static final ProductSnapshot PRODUCT =
            new ProductSnapshot("PRD-001", "BEB-600-PET", ProductStatus.ACTIVE, TaxCategory.STANDARD);

    private static MongoClient client;
    private MongoCollection<Document> orders;
    private MongoOrderResultStore store;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MongoTestContainer.MONGO.getReplicaSetUrl("store_it"));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void cleanCollection() {
        orders = client.getDatabase("store_it").getCollection("orders").withWriteConcern(WriteConcern.MAJORITY);
        orders.drop();
        OrderIndexes.ensure(orders);
        store = new MongoOrderResultStore(orders);
    }

    // ---------------------------------------------------------------- datos

    private static ValidatedOrder order(String eventId, int version) {
        return new ValidatedOrder(eventId, version, ORDER_ID, Market.MX, Currency.MXN, "CLI-1",
                List.of(new OrderItem("PRD-001", 24, new BigDecimal("35.50"))));
    }

    private static ProcessingOutcome.Approved approvedOutcome() {
        PricedOrderLine line = new PricedOrderLine("PRD-001", "BEB-600-PET", TaxCategory.STANDARD, 24,
                new BigDecimal("35.50"), new BigDecimal("852.00"), new BigDecimal("0.03"), new BigDecimal("25.56"),
                new BigDecimal("826.44"), new BigDecimal("0.16"), new BigDecimal("132.23"), new BigDecimal("958.67"));
        return new ProcessingOutcome.Approved(CLIENT, List.of(line), OrderTotals.aggregate(List.of(line)));
    }

    private static ProcessedOrder approved(String eventId, int version, String outboxEventId, Instant at) {
        return new ProcessedOrder(order(eventId, version), approvedOutcome(), SOURCE, at, at, Optional.of(outboxEventId));
    }

    private static ProcessedOrder approved(String eventId, int version, String outboxEventId) {
        return approved(eventId, version, outboxEventId, T0);
    }

    private static ProcessedOrder rejected(String eventId, int version, String outboxEventId) {
        var outcome = new ProcessingOutcome.Rejected(Optional.empty(),
                List.of(RejectionReason.CLIENT_NOT_FOUND, RejectionReason.PRODUCT_DISCONTINUED));
        return new ProcessedOrder(order(eventId, version), outcome, SOURCE, T0, T0, Optional.of(outboxEventId));
    }

    private static ProcessedOrder technicalFailure(String eventId, int version) {
        var outcome = new ProcessingOutcome.TechnicalFailure(
                ErrorCategory.EXTERNAL_TRANSIENT, "products-api: HTTP 503 after 3 attempt(s)", 3);
        return new ProcessedOrder(order(eventId, version), outcome, SOURCE, T0, T0, Optional.empty());
    }

    private Document stored() {
        return orders.find(Filters.eq("_id", ORDER_ID)).first();
    }

    private static Document outbox(Document stored) {
        return stored.get("outbox", Document.class);
    }

    /** Lanza todas las tareas a la vez detras de una barrera de salida y devuelve sus resultados. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    // ---------------------------------------------------------------- §6.3 caso 1: mismo evento, en simultaneo

    @Test
    void theSameEventProcessedByTenThreadsLeavesOneDocumentAndOneOutboxEvent() throws Exception {
        // Caso de uso completo contra Mongo real. El cliente "responde" solo cuando los 10
        // hilos llegaron a pedirlo: asi los 10 pasaron el chequeo temprano sin ver nada
        // persistido, y 9 de ellos tienen que perder en la escritura condicional (E11000).
        CyclicBarrier allPastTheEarlyCheck = new CyclicBarrier(10);
        List<ProcessedOrderSpy> published = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService enrichment = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessOrderUseCase useCase = new ProcessOrderUseCase(
                    clientId -> {
                        try {
                            allPastTheEarlyCheck.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return Optional.of(CLIENT);
                    },
                    (productId, market) -> Optional.of(PRODUCT),
                    store,
                    processed -> published.add(new ProcessedOrderSpy(processed.outboxEventId().orElseThrow())),
                    () -> UUID.randomUUID().toString(),
                    new NoOpMetrics(),
                    Clock.fixed(T0, ZoneOffset.UTC),
                    enrichment,
                    Duration.ofSeconds(20));
            ProcessOrderCommand event = new ProcessOrderCommand(new IncomingOrder("EVT-1", 1, ORDER_ID, "MX", "MXN", "CLI-1",
                    List.of(new OrderItem("PRD-001", 24, new BigDecimal("35.50")))), SOURCE);

            List<ProcessOrderResult> results = runConcurrently(
                    Collections.nCopies(10, () -> useCase.process(event)));

            assertThat(results).containsOnlyOnce(new ProcessOrderResult.Completed(OrderStatus.APPROVED));
            assertThat(results).filteredOn(new ProcessOrderResult.Skipped(ErrorCategory.DUPLICATE)::equals).hasSize(9);
        }

        assertThat(orders.countDocuments()).isEqualTo(1);
        Document outbox = outbox(stored());
        assertThat(published).singleElement().extracting(ProcessedOrderSpy::outboxEventId)
                .isEqualTo(outbox.getString("eventId"));
        assertThat(outbox.getString("status")).isEqualTo("PUBLISHED");
    }

    private record ProcessedOrderSpy(String outboxEventId) {
    }

    private static final class NoOpMetrics implements com.mariposa.b2b.orderprocessor.application.port.out.OrderMetrics {
        @Override
        public void processed(OrderStatus status, Market market) {
        }

        @Override
        public void rejected(RejectionReason reason) {
        }

        @Override
        public void duplicate() {
        }

        @Override
        public void stale() {
        }
    }

    @Test
    void tenConcurrentWritesOfTheSameEventApplyExactlyOnce() throws Exception {
        List<Callable<Map.Entry<String, WriteResult>>> writes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String outboxEventId = "OUT-" + i;
            writes.add(() -> Map.entry(outboxEventId, store.save(approved("EVT-1", 1, outboxEventId))));
        }

        List<Map.Entry<String, WriteResult>> results = runConcurrently(writes);

        Map<WriteResult, List<String>> byResult = results.stream().collect(Collectors.groupingBy(
                Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        assertThat(byResult.get(WriteResult.APPLIED)).hasSize(1);
        assertThat(byResult.get(WriteResult.DUPLICATE)).hasSize(9);
        assertThat(orders.countDocuments()).isEqualTo(1);
        assertThat(outbox(stored()).getString("eventId")).isEqualTo(byResult.get(WriteResult.APPLIED).get(0));
    }

    // ---------------------------------------------------------------- §6.3 caso 2: misma version, distinto eventId

    @Test
    void theSameVersionWithAnotherEventIdIsAConflictAndTheFirstWriterWins() {
        store.save(approved("EVT-1", 1, "OUT-1"));

        WriteResult result = store.save(rejected("EVT-OTHER", 1, "OUT-2"));

        assertThat(result).isEqualTo(WriteResult.VERSION_CONFLICT);
        Document stored = stored();
        assertThat(stored.getString("eventId")).isEqualTo("EVT-1");
        assertThat(stored.getString("status")).isEqualTo("APPROVED");
        assertThat(outbox(stored).getString("eventId")).isEqualTo("OUT-1");
    }

    @Test
    void twoConcurrentEventsWithTheSameVersionLeaveOneWinnerAndOneConflict() throws Exception {
        List<WriteResult> results = runConcurrently(List.of(
                () -> store.save(approved("EVT-A", 1, "OUT-A")),
                () -> store.save(approved("EVT-B", 1, "OUT-B"))));

        assertThat(results).containsExactlyInAnyOrder(WriteResult.APPLIED, WriteResult.VERSION_CONFLICT);
        Document stored = stored();
        String winner = results.get(0) == WriteResult.APPLIED ? "A" : "B";
        assertThat(stored.getString("eventId")).isEqualTo("EVT-" + winner);
        assertThat(outbox(stored).getString("eventId")).isEqualTo("OUT-" + winner);
    }

    // ---------------------------------------------------------------- §6.3 caso 3: version menor despues de una mayor

    @Test
    void anOlderVersionAfterANewerOneIsStaleAndLeavesTheNewerIntact() {
        store.save(approved("EVT-2", 2, "OUT-2"));
        Document before = stored();

        WriteResult result = store.save(rejected("EVT-1", 1, "OUT-1"));

        assertThat(result).isEqualTo(WriteResult.STALE);
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void anOlderTechnicalFailureCannotOverwriteANewerResultEither() {
        store.save(approved("EVT-2", 2, "OUT-2"));

        assertThat(store.save(technicalFailure("EVT-1", 1))).isEqualTo(WriteResult.STALE);
        assertThat(stored().getString("status")).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- resto de la tabla §6.2

    @Test
    void theSameEventTwiceIsADuplicateAndKeepsTheOriginalOutboxEvent() {
        store.save(approved("EVT-1", 1, "OUT-1"));

        assertThat(store.save(approved("EVT-1", 1, "OUT-1-again"))).isEqualTo(WriteResult.DUPLICATE);
        assertThat(outbox(stored()).getString("eventId")).isEqualTo("OUT-1");
    }

    @Test
    void aNewerVersionReplacesTheResultAndKeepsFirstReceivedAt() {
        store.save(approved("EVT-1", 1, "OUT-1", T0));
        Instant later = T0.plusSeconds(60);

        WriteResult result = store.save(approved("EVT-2", 2, "OUT-2", later));

        assertThat(result).isEqualTo(WriteResult.APPLIED);
        Document stored = stored();
        assertThat(stored.getInteger("eventVersion")).isEqualTo(2);
        assertThat(stored.getDate("firstReceivedAt")).isEqualTo(Date.from(T0));
        assertThat(stored.getDate("receivedAt")).isEqualTo(Date.from(later));
        assertThat(outbox(stored).getString("eventId")).isEqualTo("OUT-2");
        assertThat(outbox(stored).getString("status")).isEqualTo("PENDING");
    }

    @Test
    void theSameEventIsAcceptedAgainOverItsOwnTechnicalFailure() {
        store.save(technicalFailure("EVT-1", 1));

        assertThat(store.save(approved("EVT-1", 1, "OUT-1"))).isEqualTo(WriteResult.APPLIED);
        assertThat(stored().getString("status")).isEqualTo("APPROVED");
        assertThat(stored().get("technicalError")).isNull();
    }

    @Test
    void anotherEventOverATechnicalFailureOfTheSameVersionIsStillAConflict() {
        store.save(technicalFailure("EVT-1", 1));

        assertThat(store.save(approved("EVT-OTHER", 1, "OUT-1"))).isEqualTo(WriteResult.VERSION_CONFLICT);
        assertThat(stored().getString("status")).isEqualTo("TECHNICAL_FAILURE");
    }

    @Test
    void aTechnicalFailureIsPersistedWithoutOutboxAndRemovesThePreviousOne() {
        store.save(approved("EVT-1", 1, "OUT-1"));

        assertThat(store.save(technicalFailure("EVT-2", 2))).isEqualTo(WriteResult.APPLIED);

        Document stored = stored();
        assertThat(stored.containsKey("outbox")).isFalse();
        assertThat(stored.get("technicalError", Document.class)).isEqualTo(new Document()
                .append("category", "EXTERNAL_TRANSIENT")
                .append("cause", "products-api: HTTP 503 after 3 attempt(s)")
                .append("attempts", 3));
    }

    @Test
    void findRevisionReadsWhatTheConditionalWriteNeeds() {
        assertThat(store.findRevision(ORDER_ID)).isEmpty();
        store.save(technicalFailure("EVT-1", 1));

        assertThat(store.findRevision(ORDER_ID)).contains(new StoredRevision(1, "EVT-1", OrderStatus.TECHNICAL_FAILURE));
    }

    // ---------------------------------------------------------------- forma del documento (§7.1)

    @Test
    void storesAmountsAsDecimal128WithScaleTwoAndTheProcessedEventAsPayload() {
        store.save(approved("EVT-1", 1, "OUT-1"));

        Document stored = stored();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(stored.getString("market")).isEqualTo("MX");
        assertThat(stored.get("client", Document.class).getString("segment")).isEqualTo("WHOLESALE");
        Document line = stored.getList("lines", Document.class).get(0);
        assertThat(line.get("lineTotal")).isEqualTo(new Decimal128(new BigDecimal("958.67")));
        assertThat(line.get("discountRate")).isEqualTo(new Decimal128(new BigDecimal("0.03")));
        assertThat(stored.get("totals", Document.class).get("grandTotal"))
                .isInstanceOf(Decimal128.class).hasToString("958.67");
        assertThat(stored.get("trace", Document.class)).isEqualTo(
                new Document("sourceTopic", "orders.created.v1").append("partition", 1).append("offset", 42L));

        Document outbox = outbox(stored);
        assertThat(outbox.getInteger("publishAttempts")).isZero();
        assertThat(outbox.get("publishedAt")).isNull();
        Document payload = outbox.get("payload", Document.class);
        assertThat(payload.getString("eventId")).isEqualTo("OUT-1");
        assertThat(payload.getString("sourceEventId")).isEqualTo("EVT-1");
        assertThat(payload.getString("status")).isEqualTo("APPROVED");
        assertThat(payload.get("totals", Document.class).get("grandTotal")).hasToString("958.67");
        assertThat(payload.containsKey("reason")).isTrue();
        assertThat(payload.get("reason")).isNull();
    }

    @Test
    void aRejectedOrderHasNoTotalsAndPublishesItsFirstReason() {
        store.save(rejected("EVT-1", 1, "OUT-1"));

        Document stored = stored();
        assertThat(stored.get("totals")).isNull();
        assertThat(stored.get("client")).isNull();
        assertThat(stored.getString("clientId")).isEqualTo("CLI-1");
        assertThat(stored.getList("rejectionReasons", String.class)).containsExactly("CLIENT_NOT_FOUND", "PRODUCT_DISCONTINUED");
        Document payload = outbox(stored).get("payload", Document.class);
        assertThat(payload.getString("reason")).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(payload.containsKey("totals")).isTrue();
        assertThat(payload.get("totals")).isNull();
    }

    // ---------------------------------------------------------------- outbox

    @Test
    void markPublishedOnlyTouchesTheMatchingPendingEvent() {
        store.save(approved("EVT-1", 1, "OUT-1"));

        store.markPublished(ORDER_ID, "OUT-STALE", T0.plusSeconds(1));
        assertThat(outbox(stored()).getString("status")).isEqualTo("PENDING");

        store.markPublished(ORDER_ID, "OUT-1", T0.plusSeconds(2));
        store.markPublished(ORDER_ID, "OUT-1", T0.plusSeconds(3));

        Document outbox = outbox(stored());
        assertThat(outbox.getString("status")).isEqualTo("PUBLISHED");
        assertThat(outbox.getDate("publishedAt")).isEqualTo(Date.from(T0.plusSeconds(2)));
        assertThat(outbox.getInteger("publishAttempts")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- indices (§7.4) y fallos

    @Test
    void createsTheIndexesIdempotentlyWithAPartialFilterForPendingOutbox() {
        OrderIndexes.ensure(orders);

        Map<String, Document> indexes = orders.listIndexes().into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(index -> index.getString("name"), Function.identity()));
        assertThat(indexes).containsOnlyKeys("_id_", OrderIndexes.BY_EVENT_ID, OrderIndexes.OUTBOX_PENDING, OrderIndexes.BY_STATUS_RECENT);
        assertThat(indexes.get(OrderIndexes.BY_EVENT_ID).get("key", Document.class)).isEqualTo(new Document("eventId", 1));
        assertThat(indexes.get(OrderIndexes.OUTBOX_PENDING).get("key", Document.class))
                .isEqualTo(new Document("outbox.status", 1).append("outbox.createdAt", 1));
        assertThat(indexes.get(OrderIndexes.OUTBOX_PENDING).get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("outbox.status", "PENDING"));
        assertThat(indexes.get(OrderIndexes.BY_STATUS_RECENT).get("key", Document.class))
                .isEqualTo(new Document("status", 1).append("processedAt", -1));
    }

    @Test
    void anUnavailableDatabaseIsAStorageFailureNotADuplicate() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost:" + closedPort))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(300, TimeUnit.MILLISECONDS))
                .build();
        try (MongoClient down = MongoClients.create(settings)) {
            MongoOrderResultStore unavailable = new MongoOrderResultStore(down.getDatabase("x").getCollection("orders"));

            assertThatThrownBy(() -> unavailable.save(approved("EVT-1", 1, "OUT-1"))).isInstanceOf(OrderStorageException.class);
            assertThatThrownBy(() -> unavailable.findRevision(ORDER_ID)).isInstanceOf(OrderStorageException.class);
            assertThatThrownBy(() -> unavailable.markPublished(ORDER_ID, "OUT-1", T0)).isInstanceOf(OrderStorageException.class);
        }
    }
}
