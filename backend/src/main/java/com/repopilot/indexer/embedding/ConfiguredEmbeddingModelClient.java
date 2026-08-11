package com.repopilot.indexer.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ConfiguredEmbeddingModelClient implements EmbeddingModelClient {

    private static final int ERROR_EXCERPT_LIMIT = 2_000;

    private final ObjectMapper objectMapper;
    private final EmbeddingConfiguration configuration;
    private final HttpClient httpClient;

    @Autowired
    public ConfiguredEmbeddingModelClient(ObjectMapper objectMapper, EmbeddingConfiguration configuration) {
        this(
                objectMapper,
                configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        );
    }

    ConfiguredEmbeddingModelClient(
            ObjectMapper objectMapper,
            EmbeddingConfiguration configuration,
            HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.configuration = configuration;
        this.httpClient = httpClient;
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        if (!configuration.embeddingReady()) {
            throw new EmbeddingModelException(
                    "Embedding configuration is not ready: " + String.join(", ", configuration.missingRequirements())
            );
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new EmbeddingModelException("Embedding input must not be empty");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", configuration.model(),
                    "input", inputs
            ));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(configuration.apiBaseUrl() + "/embeddings"))
                    .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (configuration.apiKeyConfigured()) {
                request.header("Authorization", "Bearer " + configuration.apiKey());
            }

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbeddingModelException(
                        "Embedding model returned HTTP " + response.statusCode() + ": " + excerpt(response.body())
                );
            }
            return parseResponse(response.body(), inputs.size());
        } catch (JsonProcessingException exception) {
            throw new EmbeddingModelException("Failed to serialize embedding request", exception);
        } catch (IllegalArgumentException exception) {
            throw new EmbeddingModelException("Embedding endpoint configuration is invalid", exception);
        } catch (IOException exception) {
            throw new EmbeddingModelException("Embedding model request failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingModelException("Embedding model request interrupted", exception);
        }
    }

    private EmbeddingBatch parseResponse(String responseBody, int expectedCount) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new EmbeddingModelException("Embedding response does not include a data array");
        }

        List<IndexedVector> indexedVectors = new ArrayList<>();
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embedding = item.path("embedding");
            if (index < 0 || !embedding.isArray() || embedding.isEmpty()) {
                throw new EmbeddingModelException("Embedding response contains an invalid item");
            }
            List<Double> vector = new ArrayList<>(embedding.size());
            for (JsonNode value : embedding) {
                double number = value.asDouble(Double.NaN);
                if (!Double.isFinite(number)) {
                    throw new EmbeddingModelException("Embedding response contains a non-finite value");
                }
                vector.add(number);
            }
            indexedVectors.add(new IndexedVector(index, List.copyOf(vector)));
        }
        indexedVectors.sort(Comparator.comparingInt(IndexedVector::index));
        validateVectors(indexedVectors, expectedCount);

        String responseModel = root.path("model").asText(configuration.model());
        List<List<Double>> vectors = indexedVectors.stream().map(IndexedVector::vector).toList();
        return new EmbeddingBatch(configuration.provider(), responseModel, vectors.get(0).size(), vectors);
    }

    private void validateVectors(List<IndexedVector> vectors, int expectedCount) {
        if (vectors.size() != expectedCount) {
            throw new EmbeddingModelException(
                    "Embedding response count mismatch: expected " + expectedCount + " but got " + vectors.size()
            );
        }
        int dimension = vectors.get(0).vector().size();
        if (dimension > 16_000) {
            throw new EmbeddingModelException("Embedding dimension exceeds pgvector limit of 16000");
        }
        Map<Integer, Boolean> indexes = new LinkedHashMap<>();
        for (int position = 0; position < vectors.size(); position++) {
            IndexedVector vector = vectors.get(position);
            if (vector.index() != position || indexes.put(vector.index(), true) != null) {
                throw new EmbeddingModelException("Embedding response indexes are incomplete or duplicated");
            }
            if (vector.vector().size() != dimension) {
                throw new EmbeddingModelException("Embedding response dimensions are inconsistent");
            }
        }
    }

    private String excerpt(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= ERROR_EXCERPT_LIMIT ? compact : compact.substring(0, ERROR_EXCERPT_LIMIT) + "...";
    }

    private record IndexedVector(int index, List<Double> vector) {
    }
}
