package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.ContractSchemas;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Currency;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.OrderTotals;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventJsonTest {

    private static final Instant AT = Instant.parse("2026-10-26T12:00:00.250Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessedEventJson json = new ProcessedEventJson(mapper);

    private static ValidatedOrder order() {
        return new ValidatedOrder("EVT-1", 3, "ORD-MX-000147", Market.MX, Currency.MXN, "CLI-1",
                List.of(new OrderItem("PRD-001", 24, new BigDecimal("35.50"))));
    }

    private static ProcessedOrder processed(ProcessingOutcome outcome) {
        return new ProcessedOrder(order(), outcome, new ProcessOrderCommand.Source("orders.created.v1", 0, 1L), AT, AT,
                Optional.of("OUT-1"));
    }

    private static ProcessingOutcome.Approved approved() {
        PricedOrderLine line = new PricedOrderLine("PRD-001", "BEB-600-PET", TaxCategory.EXEMPT, 24,
                new BigDecimal("35.50"), new BigDecimal("852.00"), new BigDecimal("0.03"), new BigDecimal("25.56"),
                new BigDecimal("826.44"), BigDecimal.ZERO, new BigDecimal("0.00"), new BigDecimal("826.44"));
        ClientSnapshot client = new ClientSnapshot("CLI-1", ClientStatus.ACTIVE, ClientSegment.WHOLESALE, TaxRegime.GENERAL, Market.MX);
        return new ProcessingOutcome.Approved(client, List.of(line), OrderTotals.aggregate(List.of(line)));
    }

    @Test
    void anApprovedEventMatchesTheContractWithExactAmounts() throws IOException {
        byte[] bytes = json.toJson(OrderDocumentMapper.payload(processed(approved())));

        assertThat(ContractSchemas.violations("orders.processed.v1", bytes)).isEmpty();
        String text = new String(bytes, StandardCharsets.UTF_8);
        // Numeros JSON con la escala del calculo, sin notacion cientifica ni paso por double.
        assertThat(text).contains("\"grandTotal\":826.44", "\"tax\":0.00", "\"discount\":25.56");
        JsonNode tree = mapper.readTree(bytes);
        assertThat(tree.get("eventId").asText()).isEqualTo("OUT-1");
        assertThat(tree.get("sourceEventId").asText()).isEqualTo("EVT-1");
        assertThat(tree.get("eventVersion").asInt()).isEqualTo(3);
        assertThat(tree.get("occurredAt").asText()).isEqualTo("2026-10-26T12:00:00.250Z");
        assertThat(tree.get("reason").isNull()).isTrue();
    }

    @Test
    void aRejectedEventMatchesTheContractWithNullTotalsAndTheFirstReason() throws IOException {
        var rejected = new ProcessingOutcome.Rejected(Optional.empty(),
                List.of(RejectionReason.CLIENT_NOT_FOUND, RejectionReason.PRODUCT_NOT_FOUND));

        byte[] bytes = json.toJson(OrderDocumentMapper.payload(processed(rejected)));

        assertThat(ContractSchemas.violations("orders.processed.v1", bytes)).isEmpty();
        JsonNode tree = mapper.readTree(bytes);
        assertThat(tree.get("totals").isNull()).isTrue();
        assertThat(tree.get("reason").asText()).isEqualTo("CLIENT_NOT_FOUND");
    }

    @Test
    void theSchemaCheckIsRealAndCatchesABrokenEvent() {
        byte[] broken = "{\"eventId\":\"x\",\"status\":\"TECHNICAL_FAILURE\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(ContractSchemas.violations("orders.processed.v1", broken)).isNotEmpty();
    }
}
