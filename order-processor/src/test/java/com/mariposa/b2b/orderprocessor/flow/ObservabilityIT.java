package com.mariposa.b2b.orderprocessor.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.KafkaTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Anexo de observabilidad: logs JSON con MDC y una linea por transicion, y las metricas
 * en /actuator/prometheus. Usa la aplicacion completa, no mocks del logger ni del registry.
 * {@code @AutoConfigureObservability}: en los tests, Boot desactiva el registry de
 * Prometheus salvo que se pida explicitamente.
 */
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends FlowTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Lineas JSON del log con transition para ese orderId, en orden. */
    private List<JsonNode> transitions(CapturedOutput output, String orderId) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.getOut().split("\\R")) {
            if (!line.startsWith("{") || !line.contains(orderId)) {
                continue;
            }
            try {
                JsonNode node = json.readTree(line);
                if (orderId.equals(node.path("orderId").asText()) && node.has("transition")) {
                    lines.add(node);
                }
            } catch (IOException e) {
                throw new AssertionError("log line is not JSON: " + line, e);
            }
        }
        return lines;
    }

    private static List<String> names(List<JsonNode> lines) {
        return lines.stream().map(line -> line.get("transition").asText()).toList();
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    /** Suma de las series de una metrica que tienen todas las etiquetas dadas (formato texto de Prometheus). */
    private double metric(String name, String... labels) throws IOException, InterruptedException {
        double total = 0;
        Pattern series = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*})?\\s+(\\S+)$", Pattern.MULTILINE);
        Matcher matcher = series.matcher(get("/actuator/prometheus"));
        while (matcher.find()) {
            String found = matcher.group(1) == null ? "" : matcher.group(1);
            boolean matches = true;
            for (String label : labels) {
                matches &= found.contains(label);
            }
            if (matches) {
                total += Double.parseDouble(matcher.group(2));
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- logs

    @Test
    void anApprovedOrderLeavesOneJsonLinePerTransitionWithTheMessageContext(CapturedOutput output) {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");

        publish(orderId, event("EVT-OBS-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));

        await().atMost(WAIT).until(() -> names(transitions(output, orderId)).contains("published"));
        List<JsonNode> lines = transitions(output, orderId);
        assertThat(names(lines)).containsExactly("received", "validated", "enriched", "evaluated", "persisted", "published");
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.get("eventId").asText()).isEqualTo("EVT-OBS-1");
            assertThat(line.get("eventVersion").asText()).isEqualTo("1");
            assertThat(line.has("partition")).isTrue();
            assertThat(line.has("offset")).isTrue();
            assertThat(line.get("message").asText()).isEqualTo(line.get("transition").asText());
        });
        JsonNode persisted = lines.get(4);
        assertThat(persisted.get("outcome").asText()).isEqualTo("APPROVED");
        assertThat(persisted.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void anInvalidOrderEndsWithADltLineThatCarriesTheCategory(CapturedOutput output) {
        String orderId = newOrderId();

        publish(orderId, event("EVT-OBS-2", 1, orderId, "CLI-1"));

        await().atMost(WAIT).until(() -> names(transitions(output, orderId)).contains("dlt"));
        List<JsonNode> lines = transitions(output, orderId);
        assertThat(names(lines)).containsExactly("received", "validated", "dlt");
        assertThat(lines.get(1).get("valid").asBoolean()).isFalse();
        assertThat(lines.get(2).get("category").asText()).isEqualTo("VALIDATION");
        assertThat(lines.get(2).get("eventId").asText()).isEqualTo("EVT-OBS-2");
    }

    @Test
    void anUnreadableMessageIsLoggedUnderTheOrderIdOfItsKey(CapturedOutput output) {
        String orderId = newOrderId();

        publish(orderId, "{garbage");

        await().atMost(WAIT).until(() -> names(transitions(output, orderId)).contains("dlt"));
        List<JsonNode> lines = transitions(output, orderId);
        assertThat(names(lines)).containsExactly("dlt");
        assertThat(lines.get(0).get("category").asText()).isEqualTo("DESERIALIZATION");
        assertThat(lines.get(0).has("eventId")).isFalse();
    }

    // ---------------------------------------------------------------- metricas

    @Test
    void exposesTheMetricsOfTheProposalAnnex() throws Exception {
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubClientNotFound("CLI-404");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");
        stubProductStatus("PRD-503", 503);
        double approved = metric("orders_processed_total", "status=\"APPROVED\"", "market=\"MX\"");
        double clientNotFound = metric("orders_rejected_total", "reason=\"CLIENT_NOT_FOUND\"");
        double duplicates = metric("orders_duplicates_total");
        double stale = metric("orders_stale_total");
        double validationDlt = metric("orders_dlt_total", "category=\"VALIDATION\"");
        double retries = metric("http_client_retries_total", "target=\"products-api\"");

        String ok = newOrderId();
        String okEvent = event("EVT-M-1", 2, ok, "CLI-1", item("PRD-001", 1, "10.00"));
        publish(ok, okEvent);
        publish(ok, okEvent);                                                            // duplicado
        publish(ok, event("EVT-M-0", 1, ok, "CLI-1", item("PRD-001", 1, "10.00")));      // stale
        String rejected = newOrderId();
        publish(rejected, event("EVT-M-2", 1, rejected, "CLI-404", item("PRD-001", 1, "10.00")));
        String invalid = newOrderId();
        publish(invalid, event("EVT-M-3", 1, invalid, "CLI-1"));
        String failing = newOrderId();
        publish(failing, event("EVT-M-4", 1, failing, "CLI-1", item("PRD-503", 1, "10.00")));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(metric("orders_stale_total")).isEqualTo(stale + 1);
            assertThat(metric("orders_dlt_total", "category=\"EXTERNAL_TRANSIENT\"")).isGreaterThanOrEqualTo(1);
            assertThat(metric("orders_dlt_total", "category=\"VALIDATION\"")).isEqualTo(validationDlt + 1);
            assertThat(metric("orders_rejected_total", "reason=\"CLIENT_NOT_FOUND\"")).isEqualTo(clientNotFound + 1);
        });
        assertThat(metric("orders_processed_total", "status=\"APPROVED\"", "market=\"MX\"")).isEqualTo(approved + 1);
        assertThat(metric("orders_processed_total", "status=\"TECHNICAL_FAILURE\"")).isGreaterThanOrEqualTo(1);
        assertThat(metric("orders_duplicates_total")).isEqualTo(duplicates + 1);
        // 3 intentos HTTP al producto que responde 503 = 2 reintentos.
        assertThat(metric("http_client_retries_total", "target=\"products-api\"")).isEqualTo(retries + 2);
        assertThat(metric("orders_processing_duration_seconds_count")).isGreaterThanOrEqualTo(6);
        assertThat(get("/actuator/prometheus"))
                .contains("orders_processing_duration_seconds_bucket{")
                .contains("outbox_pending_age_seconds ")
                .contains("kafka_consumer_fetch_manager_records_lag_max{");
        assertThat(records(KafkaTestContainer.PROCESSED, ok)).hasSize(1);
    }

    @Test
    void exposesTheHealthEndpointUsedByDockerCompose() throws Exception {
        assertThat(json.readTree(get("/actuator/health")).get("status").asText()).isEqualTo("UP");
    }
}
