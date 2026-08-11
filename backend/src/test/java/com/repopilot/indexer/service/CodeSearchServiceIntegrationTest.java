package com.repopilot.indexer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.indexer.domain.CodeChunk;
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeFile;
import com.repopilot.indexer.domain.CodeFileLanguage;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.embedding.CodeEmbeddingService;
import com.repopilot.indexer.repository.CodeChunkRepository;
import com.repopilot.indexer.repository.CodeEmbeddingRepository;
import com.repopilot.indexer.repository.CodeFileRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.domain.RepositorySnapshot;
import com.repopilot.repository.repository.RepositorySnapshotRepository;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class CodeSearchServiceIntegrationTest {

    private static final StubEmbeddingServer EMBEDDING_SERVER = StubEmbeddingServer.start();

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private CodeEmbeddingService codeEmbeddingService;

    @Autowired
    private CodeEmbeddingRepository codeEmbeddingRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @Autowired
    private CodeFileRepository codeFileRepository;

    @Autowired
    private RepositorySnapshotRepository snapshotRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;
    private Project project;
    private CodeChunk customerChunk;
    private CodeChunk auditChunk;

    @DynamicPropertySource
    static void embeddingProperties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.embedding.mode", () -> "openai-compatible");
        registry.add("repopilot.embedding.api-base-url", EMBEDDING_SERVER::baseUrl);
        registry.add("repopilot.embedding.api-key", () -> "");
        registry.add("repopilot.embedding.model", () -> "semantic-code-test");
        registry.add("repopilot.embedding.batch-size", () -> "8");
        registry.add("repopilot.embedding.minimum-similarity", () -> "0.20");
    }

    @BeforeEach
    void setUp() {
        EMBEDDING_SERVER.failRequests(false);
        user = userRepository.save(new User(
                "embedding-search-" + UUID.randomUUID() + "@example.test",
                "not-used",
                "Embedding Search",
                "USER"
        ));
        project = projectRepository.save(new Project(
                user,
                "https://example.test/acme/search-demo.git",
                "acme/search-demo",
                "main"
        ));
        RepositorySnapshot snapshot = snapshotRepository.save(new RepositorySnapshot(project, "main", "abc123", 2, 2));
        CodeFile customerFile = codeFileRepository.save(new CodeFile(
                project,
                snapshot,
                "src/main/java/com/acme/CustomerDirectory.java",
                CodeFileLanguage.JAVA,
                "a".repeat(64),
                120
        ));
        CodeFile auditFile = codeFileRepository.save(new CodeFile(
                project,
                snapshot,
                "src/main/java/com/acme/BillingAudit.java",
                CodeFileLanguage.JAVA,
                "b".repeat(64),
                100
        ));
        customerChunk = codeChunkRepository.save(new CodeChunk(
                project,
                customerFile,
                null,
                CodeChunkType.METHOD,
                "public List<Customer> fetchAllCustomers() { return customerRepository.findAll(); }",
                "METHOD com.acme.CustomerDirectory#fetchAllCustomers",
                10,
                12
        ));
        auditChunk = codeChunkRepository.save(new CodeChunk(
                project,
                auditFile,
                null,
                CodeChunkType.CLASS,
                "class BillingAudit { void recordInvoiceEvent() {} }",
                "CLASS com.acme.BillingAudit",
                1,
                4
        ));
        codeChunkRepository.flush();

        CodeEmbeddingService.IndexingResult result = codeEmbeddingService.indexProject(
                project.getId(),
                List.of(customerChunk, auditChunk)
        );
        assertThat(result.status()).isEqualTo("INDEXED");
        assertThat(result.embeddingCount()).isEqualTo(2);
        assertThat(result.dimension()).isEqualTo(3);
    }

    @AfterEach
    void tearDown() {
        if (project != null) {
            projectRepository.deleteById(project.getId());
        }
        if (user != null) {
            userRepository.deleteById(user.getId());
        }
    }

    @AfterAll
    static void stopServer() {
        EMBEDDING_SERVER.close();
    }

    @Test
    void semanticQueryFindsChunkWithoutKeywordOverlapThroughPgvector() {
        assertThat(codeEmbeddingRepository.countByProjectId(project.getId())).isEqualTo(2);

        CodeSearchResponse response = codeSearchService.search(project.getId(), "列出所有账户", 5);

        assertThat(response.retrievalMode()).isEqualTo("VECTOR");
        assertThat(response.retrievalModeLabel()).isEqualTo("向量语义检索");
        assertThat(response.embeddingStatus()).isEqualTo("READY");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).chunkId()).isEqualTo(customerChunk.getId());
        assertThat(response.results().get(0).matchType()).isEqualTo("VECTOR");
        assertThat(response.results().get(0).keywordScore()).isNull();
        assertThat(response.results().get(0).vectorScore()).isEqualTo(1.0);
    }

    @Test
    void keywordAndVectorCandidatesAreMergedWithSourceScores() {
        CodeSearchResponse response = codeSearchService.search(project.getId(), "BillingAudit", 5);

        assertThat(response.retrievalMode()).isEqualTo("HYBRID");
        assertThat(response.results()).isNotEmpty();
        assertThat(response.results().get(0).chunkId()).isEqualTo(auditChunk.getId());
        assertThat(response.results().get(0).matchType()).isEqualTo("HYBRID");
        assertThat(response.results().get(0).keywordScore()).isPositive();
        assertThat(response.results().get(0).vectorScore()).isEqualTo(1.0);
        assertThat(response.results().get(0).combinedScore()).isPositive();
    }

    @Test
    void providerFailureFallsBackToKeywordSearch() {
        EMBEDDING_SERVER.failRequests(true);

        CodeSearchResponse response = codeSearchService.search(project.getId(), "BillingAudit", 5);

        assertThat(response.retrievalMode()).isEqualTo("KEYWORD_FALLBACK");
        assertThat(response.embeddingStatus()).isEqualTo("FAILED");
        assertThat(response.results()).isNotEmpty();
        assertThat(response.results().get(0).chunkId()).isEqualTo(auditChunk.getId());
        assertThat(response.results().get(0).matchType()).isEqualTo("KEYWORD");
    }

    private static final class StubEmbeddingServer implements AutoCloseable {

        private final HttpServer server;
        private final ObjectMapper objectMapper;
        private final AtomicBoolean failRequests = new AtomicBoolean();

        private StubEmbeddingServer(HttpServer server, ObjectMapper objectMapper) {
            this.server = server;
            this.objectMapper = objectMapper;
        }

        static StubEmbeddingServer start() {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                StubEmbeddingServer stub = new StubEmbeddingServer(server, objectMapper);
                server.createContext("/v1/embeddings", stub::handle);
                server.start();
                return stub;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void failRequests(boolean fail) {
            failRequests.set(fail);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (failRequests.get()) {
                    respond(exchange, 503, "{\"error\":\"temporary failure\"}");
                    return;
                }
                JsonNode input = objectMapper.readTree(exchange.getRequestBody()).path("input");
                List<Object> data = new ArrayList<>();
                for (int index = 0; index < input.size(); index++) {
                    data.add(java.util.Map.of(
                            "object", "embedding",
                            "index", index,
                            "embedding", vectorFor(input.get(index).asText())
                    ));
                }
                respond(exchange, 200, objectMapper.writeValueAsString(java.util.Map.of(
                        "object", "list",
                        "model", "semantic-code-test",
                        "data", data
                )));
            } finally {
                exchange.close();
            }
        }

        private List<Double> vectorFor(String input) {
            String normalized = input.toLowerCase();
            if (normalized.contains("fetchallcustomers") || normalized.contains("列出所有账户")) {
                return List.of(1.0, 0.0, 0.0);
            }
            if (normalized.contains("billingaudit")) {
                return List.of(0.0, 1.0, 0.0);
            }
            return List.of(0.0, 0.0, 1.0);
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
