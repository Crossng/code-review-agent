package com.repopilot.indexer.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfiguredEmbeddingModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedCallsOpenAiCompatibleEndpointAndRestoresInputOrder() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {
                      "object": "list",
                      "model": "embedding-test-v2",
                      "data": [
                        {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0]},
                        {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]}
                      ]
                    }
                    """);
        });
        ConfiguredEmbeddingModelClient client = client(baseUrl, "secret-key");

        EmbeddingModelClient.EmbeddingBatch result = client.embed(List.of("first", "second"));

        assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(result.model()).isEqualTo("embedding-test-v2");
        assertThat(result.dimension()).isEqualTo(3);
        assertThat(result.vectors()).containsExactly(
                List.of(1.0, 0.0, 0.0),
                List.of(0.0, 1.0, 0.0)
        );
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("embedding-test-v1");
        assertThat(requestBody.get().path("input")).extracting(JsonNode::asText).containsExactly("first", "second");
    }

    @Test
    void embedAllowsLocalCompatibleEndpointWithoutApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"data":[{"index":0,"embedding":[0.25,0.75]}]}
                    """);
        });

        EmbeddingModelClient.EmbeddingBatch result = client(baseUrl, "").embed(List.of("local"));

        assertThat(result.dimension()).isEqualTo(2);
        assertThat(authorization.get()).isNull();
    }

    @Test
    void embedRejectsInvalidProviderResponse() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 200, """
                {"data":[{"index":0,"embedding":[1.0,0.0]},{"index":0,"embedding":[0.0,1.0]}]}
                """));

        assertThatThrownBy(() -> client(baseUrl, "").embed(List.of("first", "second")))
                .isInstanceOf(EmbeddingModelException.class)
                .hasMessageContaining("indexes");
    }

    @Test
    void embedReportsSanitizedHttpFailure() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 503, "provider unavailable"));

        assertThatThrownBy(() -> client(baseUrl, "top-secret").embed(List.of("query")))
                .isInstanceOf(EmbeddingModelException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageNotContaining("top-secret");
    }

    private ConfiguredEmbeddingModelClient client(String baseUrl, String apiKey) {
        EmbeddingConfiguration configuration = new EmbeddingConfiguration(
                "openai-compatible",
                baseUrl + "/",
                apiKey,
                "embedding-test-v1",
                5,
                16,
                8_000,
                0.45,
                0.55,
                0.2
        );
        return new ConfiguredEmbeddingModelClient(
                objectMapper,
                configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        );
    }

    private String startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
