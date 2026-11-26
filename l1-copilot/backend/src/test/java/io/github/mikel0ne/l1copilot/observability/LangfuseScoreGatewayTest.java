package io.github.mikel0ne.l1copilot.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseScoreGatewayTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Con Langfuse apagado, send no hace nada ni resuelve el RestClient.Builder")
    void sendIsNoopWhenDisabled() {
        LangfuseScoreGateway gateway = LangfuseTestTracer.disabledScoreGateway();

        gateway.send(TRACE_ID, "resolved", 1, LangfuseScoreGateway.BOOLEAN, null);
    }

    @Test
    @DisplayName("El payload es un batch con un evento score-create cuyo body apunta al traceId OTel")
    void batchHasScoreCreateEvent() throws Exception {
        JsonNode json = serialize("verify_verdict", "SURE", LangfuseScoreGateway.CATEGORICAL, null);

        assertThat(json.fieldNames()).toIterable().containsExactly("batch");
        JsonNode event = json.get("batch").get(0);
        assertThat(json.get("batch")).hasSize(1);
        assertThat(event.get("id").asText()).isEqualTo("evt-1");
        assertThat(event.get("type").asText()).isEqualTo("score-create");
        assertThat(event.get("timestamp").asText()).isEqualTo("2026-01-02T03:04:05.000Z");

        JsonNode body = event.get("body");
        assertThat(body.get("id").asText()).isEqualTo("score-1");
        assertThat(body.get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(body.get("name").asText()).isEqualTo("verify_verdict");
        assertThat(body.get("value").asText()).isEqualTo("SURE");
        assertThat(body.get("dataType").asText()).isEqualTo("CATEGORICAL");
        assertThat(body.get("environment").asText()).isEqualTo("test");
    }

    @Test
    @DisplayName("Un score BOOLEAN viaja con valor numerico 0/1 y sin comment cuando no hay")
    void booleanValueIsNumericAndNullCommentIsOmitted() throws Exception {
        JsonNode body = serialize("resolved", 1, LangfuseScoreGateway.BOOLEAN, null)
                .get("batch").get(0).get("body");

        assertThat(body.get("value").isNumber()).isTrue();
        assertThat(body.get("value").asInt()).isEqualTo(1);
        assertThat(body.has("comment")).isFalse();
    }

    @Test
    @DisplayName("Cada score lleva body.id UUID propio, unico y distinto del id del evento")
    void eachScoreHasUniqueBodyId() throws Exception {
        JsonNode a = JSON.readTree(JSON.writeValueAsString(LangfuseScoreGateway.newBatch(
                TRACE_ID, "resolved", 1, LangfuseScoreGateway.BOOLEAN, null, "test"))).get("batch").get(0);
        JsonNode b = JSON.readTree(JSON.writeValueAsString(LangfuseScoreGateway.newBatch(
                TRACE_ID, "resolved", 1, LangfuseScoreGateway.BOOLEAN, null, "test"))).get("batch").get(0);

        String idA = a.get("body").path("id").asText();
        assertThat(java.util.UUID.fromString(idA)).isNotNull();
        assertThat(idA).isNotEqualTo(b.get("body").path("id").asText());
        assertThat(idA).isNotEqualTo(a.get("id").asText());
    }

    @Test
    @DisplayName("El cliente fuerza HTTP/1.1 para no pedir el upgrade h2c que Langfuse cierra sin responder")
    void clientForcesHttp11() {
        HttpClient client;
        try {
            client = LangfuseScoreGateway.http11Client();
        } catch (java.io.UncheckedIOException e) {
            org.junit.jupiter.api.Assumptions.abort("loopback not available: " + e);
            return;
        }
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    @DisplayName("Solo un EOFException en la cadena de causas cuenta como conexion caducada")
    void staleConnectionDetection() {
        assertThat(LangfuseScoreGateway.isStaleConnection(
                new RuntimeException(new IOException("x", new EOFException())))).isTrue();
        assertThat(LangfuseScoreGateway.isStaleConnection(new RuntimeException(new IOException("x")))).isFalse();
    }

    @Test
    @DisplayName("El POST lleva Content-Length, sin Upgrade ni chunked, y un EOF se reintenta una vez")
    void postHasContentLengthNoUpgradeAndRetriesOnce() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("loopback not available: " + e);
            return;
        }
        server.createContext("/api/public/ingestion", exchange -> {
            var h = exchange.getRequestHeaders();
            seen.add(h.getFirst("Content-Length") + "|" + h.getFirst("Transfer-Encoding") + "|" + h.getFirst("Upgrade"));
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
            byte[] ok = "{\"successes\":[],\"errors\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(207, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            LangfuseProperties props = new LangfuseProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/public/otel",
                    "pk", "sk", "test", "0", 1.0, false);
            ObjectProvider<RestClient.Builder> builder = new ObjectProvider<>() {
                @Override
                public RestClient.Builder getObject() {
                    return RestClient.builder();
                }
            };
            new LangfuseScoreGateway(props, builder).send(TRACE_ID, "resolved", 1, LangfuseScoreGateway.BOOLEAN, null);
        } finally {
            server.stop(0);
        }

        assertThat(calls.get()).isEqualTo(2);
        assertThat(seen).allSatisfy(h -> {
            String[] parts = h.split("[|]");
            assertThat(parts[0]).isNotEqualTo("null");
            assertThat(parts[1]).isEqualTo("null");
            assertThat(parts[2]).isEqualTo("null");
        });
    }

    @Test
    @DisplayName("sendAsync no espera a un Langfuse lento y el score llega igualmente")
    void sendAsyncDoesNotWaitForSlowLangfuse() throws Exception {
        java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.abort("loopback not available: " + e);
            return;
        }
        server.createContext("/api/public/ingestion", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] ok = "{\"successes\":[],\"errors\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(207, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            LangfuseProperties props = new LangfuseProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/public/otel",
                    "pk", "sk", "test", "0", 1.0, false);
            ObjectProvider<RestClient.Builder> builder = new ObjectProvider<>() {
                @Override
                public RestClient.Builder getObject() {
                    return RestClient.builder();
                }
            };
            LangfuseScoreGateway gateway = new LangfuseScoreGateway(props, builder);

            long start = System.nanoTime();
            gateway.sendAsync(TRACE_ID, "resolved", 1, LangfuseScoreGateway.BOOLEAN, null);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(500);
            assertThat(received.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode serialize(String name, Object value, String dataType, String comment) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(LangfuseScoreGateway.batch("evt-1", "score-1",
                Instant.parse("2026-01-02T03:04:05.0001234Z"), TRACE_ID, name, value, dataType, comment, "test")));
    }
}
