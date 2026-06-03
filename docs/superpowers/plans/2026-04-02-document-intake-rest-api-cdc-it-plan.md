# Document Intake REST API CDC Integration Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two CDC integration tests for `document-intake-service` that POST XML to the REST API (`POST /api/v1/documents`) and verify `StartSagaCommand` messages appear on `saga.commands.orchestrator` Kafka topic via Debezium CDC.

**Architecture:** The REST controller delegates to a Camel `direct:document-intake` route which calls `SubmitDocumentUseCase`. The use case writes to the database + outbox table. Debezium CDC reads the outbox table and publishes to Kafka. The test uses `MockMvc` (HTTP layer) + a Kafka test consumer (verifying CDC) + JDBC (verifying database state).

**Tech Stack:** Spring Boot Test, MockMvc, JdbcTemplate, Awaitility, Testcontainers (external containers via `test-containers-start.sh`)

---

## File Map

```
document-intake-service/src/test/java/com/wpanther/document/intake/integration/
├── DocumentIntakeRestApiCdcIT.java    [NEW — the test class]
└── config/
    └── RestApiCdcTestConfiguration.java [NEW — test configuration]

Existing files reused (DO NOT modify):
  AbstractCdcIT.java                    [inherited — container verification, Kafka polling, JDBC, loadTestXml()]
  CdcTestConfiguration.java             [inherited pattern — infrastructure beans]
  TestKafkaConsumerConfig.java          [inherited — Kafka consumer bean]
  src/test/resources/samples/valid/TaxInvoice_2p1_valid.xml
  src/test/resources/samples/valid/Invoice_2p1_valid.xml
  src/test/resources/application-cdc-test.yml
```

---

## Task 1: Create `RestApiCdcTestConfiguration`

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/config/RestApiCdcTestConfiguration.java`

- [ ] **Step 1: Write the configuration class**

```java
package com.wpanther.document.intake.integration.config;

import com.wpanther.document.intake.infrastructure.adapter.in.web.DocumentIntakeController;
import org.apache.camel.spring.boot.CamelAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Test configuration for REST API CDC integration tests.
 * <p>
 * Differences from CdcTestConfiguration:
 * - Does NOT exclude CamelAutoConfiguration (ProducerTemplate needed by controller)
 * - Does NOT exclude Controller components (CdcTestConfiguration excludes them)
 * - Disables Kafka consumer auto-startup so the Camel Kafka consumer route is not created
 *   (the direct:document-intake route IS created and processes REST requests synchronously)
 * - Uses @AutoConfigureMockMvc for MockMvc beans
 * <p>
 * The Kafka consumer is disabled via app.kafka.consumer.auto-startup=false property
 * in the cdc-test profile. The direct:document-intake route is NOT a Kafka consumer
 * so it remains active.
 * <p>
 * Note: JdbcTemplate and ObjectMapper are NOT defined here.
 * JdbcTemplate is auto-configured by Spring Boot from the auto-configured DataSource.
 * ObjectMapper is NOT needed in this configuration (only in AbstractCdcIT for Kafka message parsing).
 */
@TestConfiguration
@EnableAutoConfiguration(exclude = {
    KafkaAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = {
    "com.wpanther.document.intake.infrastructure.persistence"
})
@EntityScan(basePackages = {
    "com.wpanther.document.intake.infrastructure.persistence"
})
@ComponentScan(
    basePackages = {
        "com.wpanther.document.intake.domain",
        "com.wpanther.document.intake.application.service",
        "com.wpanther.document.intake.infrastructure.persistence",
        "com.wpanther.document.intake.infrastructure.validation",
        "com.wpanther.document.intake.infrastructure.messaging",
        "com.wpanther.document.intake.infrastructure.config",
        "com.wpanther.saga.infrastructure"
    },
    // NOTE: Controllers are INCLUDED in this configuration (unlike CdcTestConfiguration)
    // The CamelConfig route builder is also included (ProducerTemplate needs it)
    excludeFilters = {
        // No exclusions - include everything needed for REST API + direct Camel route
    }
)
@EnableTransactionManagement
@Import(TestKafkaConsumerConfig.class)
public class RestApiCdcTestConfiguration {
}
```

- [ ] **Step 2: Verify the file compiles** (save and confirm)

---

## Task 2: Create `DocumentIntakeRestApiCdcIT` test class

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/DocumentIntakeRestApiCdcIT.java`
- Test: `src/test/resources/samples/valid/TaxInvoice_2p1_valid.xml`
- Test: `src/test/resources/samples/valid/Invoice_2p1_valid.xml`

- [ ] **Step 1: Write the test class**

```java
package com.wpanther.document.intake.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.document.intake.integration.config.RestApiCdcTestConfiguration;
import com.wpanther.document.intake.integration.config.TestKafkaConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CDC integration tests for the REST API endpoint of document-intake-service.
 * <p>
 * Verifies the complete flow: HTTP POST → REST Controller → Camel Route →
 * SubmitDocumentUseCase → Database + Outbox → Debezium CDC → Kafka.
 * <p>
 * Prerequisites:
 * 1. Start containers: ./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
 * 2. Containers must be running:
 *    - PostgreSQL: localhost:5433 (database: intake_db)
 *    - Kafka: localhost:9093
 *    - Debezium Connect: localhost:8083
 *    - Debezium connector: outbox-connector-intake (RUNNING)
 * <p>
 * Run with:
 * mvn verify -P integration -Dtest="DocumentIntakeRestApiCdcIT" -Dspring.profiles.active=cdc-test
 */
@SpringBootTest(
    classes = RestApiCdcTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.kafka.consumer.auto-startup=false"
    }
)
@ActiveProfiles("cdc-test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Document Intake REST API CDC Integration Tests")
class DocumentIntakeRestApiCdcIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaConsumer<String, String> testKafkaConsumer;

    @Autowired
    private TestKafkaConsumerConfig kafkaConfig;

    // Cache for received Kafka messages (topic -> list of records)
    private final java.util.Map<String, List<ConsumerRecord<String, String>>> receivedMessages =
        new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll
    void setupInfrastructure() throws Exception {
        // Verify external containers are accessible
        verifyExternalContainers();
        // Verify Debezium connector is running
        verifyDebeziumConnectorRunning("outbox-connector-intake");
        // Create Kafka topics needed for tests
        kafkaConfig.createTopics();
        // Subscribe to topics to verify CDC messages
        testKafkaConsumer.subscribe(List.of(
            "saga.commands.orchestrator",
            "trace.document.received"
        ));
    }

    @BeforeEach
    void cleanupTestData() {
        // Clean tables in correct order (foreign key constraints)
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM incoming_invoices");
        receivedMessages.clear();
    }

    private void verifyExternalContainers() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assertThat(result).isEqualTo(1);

            var props = new java.util.Properties();
            props.put("bootstrap.servers", "localhost:9093");
            try (var adminClient = org.apache.kafka.clients.admin.AdminClient.create(props)) {
                adminClient.listTopics().names().get();
            }

            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:8083/connectors"))
                .GET()
                .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Debezium Connect returned status " + response.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                "\n\n" +
                "==========================================================\n" +
                "External containers are not accessible!\n" +
                "==========================================================\n" +
                "Please start them first:\n" +
                "  cd /home/wpanther/projects/etax/invoice-microservices\n" +
                "  ./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors\n\n" +
                "Error: " + e.getMessage() + "\n", e);
        }
    }

    private void verifyDebeziumConnectorRunning(String connectorName) {
        await().atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofSeconds(5))
            .until(() -> isConnectorRunning(connectorName));
    }

    private boolean isConnectorRunning(String connectorName) {
        try {
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:8083/connectors/" + connectorName + "/status"))
                .GET()
                .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.body().contains("\"state\":\"RUNNING\"");
        } catch (Exception e) {
            return false;
        }
    }

    private void pollKafkaMessages() {
        var records = testKafkaConsumer.poll(Duration.ofMillis(500));
        for (var record : records) {
            receivedMessages.computeIfAbsent(record.topic(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(record);
        }
    }

    private boolean hasMessageOnTopic(String topic, String partitionKey) {
        pollKafkaMessages();
        var messages = receivedMessages.get(topic);
        if (messages == null) return false;
        return messages.stream().anyMatch(r -> partitionKey.equals(r.key()));
    }

    private List<ConsumerRecord<String, String>> getMessagesFromTopic(String topic, String partitionKey) {
        pollKafkaMessages();
        var messages = receivedMessages.get(topic);
        if (messages == null) return List.of();
        return messages.stream().filter(r -> partitionKey.equals(r.key())).toList();
    }

    private String loadTestXml(String filename) throws Exception {
        var path = Path.of(getClass().getClassLoader()
            .getResource("samples/valid/" + filename).toURI());
        return java.nio.file.Files.readString(path);
    }

    private ConditionFactory await() {
        return Awaitility.await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofSeconds(2));
    }

    // ========================================================================
    // TC-01: Submit Tax Invoice via REST API → saga.commands.orchestrator CDC
    // ========================================================================

    @Test
    @DisplayName("TC-01: POST valid TaxInvoice XML → 202 Accepted → StartSagaCommand on saga.commands.orchestrator via CDC")
    void shouldSubmitTaxInvoiceViaRestApiAndProduceCdcMessage() throws Exception {
        // Given
        String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
        String correlationId = UUID.randomUUID().toString();

        // When - POST to REST API
        MvcResult result = mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content(xml)
                .header("X-Correlation-ID", correlationId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value(correlationId))
            .andExpect(jsonPath("$.message").value("Document submitted for processing"))
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode responseJson = objectMapper.readTree(responseBody);
        String returnedCorrelationId = responseJson.get("correlationId").asText();
        assertThat(returnedCorrelationId).isEqualTo(correlationId);

        // Then - verify database state
        await().until(() -> {
            var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incoming_invoices WHERE document_type = 'TAX_INVOICE'",
                Integer.class);
            return count != null && count > 0;
        });

        var invoiceRows = jdbcTemplate.queryForList(
            "SELECT * FROM incoming_invoices WHERE document_type = 'TAX_INVOICE' AND correlation_id = ?",
            correlationId);
        assertThat(invoiceRows).isNotEmpty();
        assertThat(invoiceRows.get(0).get("status")).isEqualTo("FORWARDED");

        // Then - verify outbox event was written
        var outboxEvents = jdbcTemplate.queryForList(
            "SELECT * FROM outbox_events WHERE partition_key = ? AND event_type = 'StartSagaCommand'",
            correlationId);
        assertThat(outboxEvents).isNotEmpty();
        assertThat(outboxEvents.get(0).get("topic")).isEqualTo("saga.commands.orchestrator");

        // Then - verify CDC message on Kafka
        await().until(() -> hasMessageOnTopic("saga.commands.orchestrator", correlationId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("saga.commands.orchestrator", correlationId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = objectMapper.readTree(messages.get(0).value());
        assertThat(payload.get("documentType").asText()).isEqualTo("TAX_INVOICE");
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(payload.has("documentId")).isTrue();
        assertThat(payload.has("xmlContent")).isTrue();
    }

    // ========================================================================
    // TC-02: Submit Invoice via REST API → saga.commands.orchestrator CDC
    // ========================================================================

    @Test
    @DisplayName("TC-02: POST valid Invoice XML → 202 Accepted → StartSagaCommand on saga.commands.orchestrator via CDC")
    void shouldSubmitInvoiceViaRestApiAndProduceCdcMessage() throws Exception {
        // Given
        String xml = loadTestXml("Invoice_2p1_valid.xml");
        String correlationId = UUID.randomUUID().toString();

        // When - POST to REST API
        MvcResult result = mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content(xml)
                .header("X-Correlation-ID", correlationId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value(correlationId))
            .andExpect(jsonPath("$.message").value("Document submitted for processing"))
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode responseJson = objectMapper.readTree(responseBody);
        String returnedCorrelationId = responseJson.get("correlationId").asText();
        assertThat(returnedCorrelationId).isEqualTo(correlationId);

        // Then - verify database state
        await().until(() -> {
            var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incoming_invoices WHERE document_type = 'INVOICE'",
                Integer.class);
            return count != null && count > 0;
        });

        var invoiceRows = jdbcTemplate.queryForList(
            "SELECT * FROM incoming_invoices WHERE document_type = 'INVOICE' AND correlation_id = ?",
            correlationId);
        assertThat(invoiceRows).isNotEmpty();
        assertThat(invoiceRows.get(0).get("status")).isEqualTo("FORWARDED");

        // Then - verify outbox event was written
        var outboxEvents = jdbcTemplate.queryForList(
            "SELECT * FROM outbox_events WHERE partition_key = ? AND event_type = 'StartSagaCommand'",
            correlationId);
        assertThat(outboxEvents).isNotEmpty();
        assertThat(outboxEvents.get(0).get("topic")).isEqualTo("saga.commands.orchestrator");

        // Then - verify CDC message on Kafka
        await().until(() -> hasMessageOnTopic("saga.commands.orchestrator", correlationId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("saga.commands.orchestrator", correlationId);
        assertThat(messages).isNotEmpty();

        JsonNode payload = objectMapper.readTree(messages.get(0).value());
        assertThat(payload.get("documentType").asText()).isEqualTo("INVOICE");
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(payload.has("documentId")).isTrue();
        assertThat(payload.has("xmlContent")).isTrue();
    }

    // ========================================================================
    // TC-03: Trace event published to trace.document.received via CDC
    // ========================================================================

    @Test
    @DisplayName("TC-03: POST TaxInvoice → DocumentReceivedTraceEvent published to trace.document.received via CDC")
    void shouldPublishTraceEventToKafkaViaCdc() throws Exception {
        // Given
        String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
        String correlationId = UUID.randomUUID().toString();

        // When
        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content(xml)
                .header("X-Correlation-ID", correlationId))
            .andExpect(status().isAccepted());

        // Then - verify trace event CDC message on Kafka
        await().until(() -> hasMessageOnTopic("trace.document.received", correlationId));

        List<ConsumerRecord<String, String>> messages =
            getMessagesFromTopic("trace.document.received", correlationId);
        assertThat(messages).isNotEmpty();

        // The last trace event should be FORWARDED
        ConsumerRecord<String, String> lastMessage = messages.get(messages.size() - 1);
        JsonNode payload = objectMapper.readTree(lastMessage.value());
        assertThat(payload.get("status").asText()).isEqualTo("FORWARDED");
        assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(payload.get("documentType").asText()).isEqualTo("TAX_INVOICE");
    }
}
```

Note: Add `import java.nio.file.Path;` at the top of the file.

- [ ] **Step 2: Verify imports and compilation** (save and confirm)

---

## Task 3: Verify the test resources exist

**Files:**
- Reuse: `src/test/resources/samples/valid/TaxInvoice_2p1_valid.xml` (already exists)
- Reuse: `src/test/resources/samples/valid/Invoice_2p1_valid.xml` (already exists)
- Reuse: `src/test/resources/application-cdc-test.yml` (already exists)

- [ ] **Step 1: Confirm the XML sample files exist and are valid**

Run: `ls -la src/test/resources/samples/valid/TaxInvoice_2p1_valid.xml src/test/resources/samples/valid/Invoice_2p1_valid.xml`
Expected: Both files exist

---

## Task 4: Run the tests

**Prerequisites:** Containers must be running.

- [ ] **Step 1: Start containers (if not already running)**

```bash
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```

- [ ] **Step 2: Run the tests**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/document-intake-service
mvn verify -P integration -Dtest="DocumentIntakeRestApiCdcIT" -Dspring.profiles.active=cdc-test
```

Expected:
- TC-01: PASS — TaxInvoice REST → 202 → CDC message on `saga.commands.orchestrator`
- TC-02: PASS — Invoice REST → 202 → CDC message on `saga.commands.orchestrator`
- TC-03: PASS — Trace event on `trace.document.received`

- [ ] **Step 3: If tests fail with "ProducerTemplate not available"**

This means Camel was excluded. Verify `RestApiCdcTestConfiguration` does NOT exclude `CamelAutoConfiguration`.

- [ ] **Step 4: If tests fail with "Kafka consumer route not found"**

Verify `app.kafka.consumer.auto-startup=false` is set. The direct route (`direct:document-intake`) should still be active.

---

## Task 5: Commit

- [ ] **Commit the new test files**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/document-intake-service
git add src/test/java/com/wpanther/document/intake/integration/DocumentIntakeRestApiCdcIT.java
git add src/test/java/com/wpanther/document/intake/integration/config/RestApiCdcTestConfiguration.java
git commit -m "test: add REST API CDC integration tests for document intake

Add DocumentIntakeRestApiCdcIT with 3 test cases:
- TC-01: POST TaxInvoice XML → StartSagaCommand via CDC
- TC-02: POST Invoice XML → StartSagaCommand via CDC  
- TC-03: POST TaxInvoice → DocumentReceivedTraceEvent via CDC

Extends AbstractCdcIT for container verification and Kafka polling.
Uses MockMvc for REST API calls with auto-startup disabled on Kafka consumer.
Prerequisites: ./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors"
```

---

## Self-Review Checklist

- [ ] Spec coverage: Both test cases (Tax Invoice, Invoice) and CDC verification are in Task 2
- [ ] Placeholder scan: No "TBD", "TODO", or vague steps — all code is concrete
- [ ] Type consistency: `KafkaConsumer<String, String>`, `MockMvc`, `JdbcTemplate`, `ObjectMapper` all match existing patterns
- [ ] `KafkaAutoConfiguration` is excluded (no Kafka consumer startup), `CamelAutoConfiguration` is NOT excluded (ProducerTemplate needed)
- [ ] `app.kafka.consumer.auto-startup=false` property prevents Kafka consumer route from being created, but `direct:document-intake` route IS created (it's not Kafka-based)
- [ ] `loadTestXml()` uses `Path.of(...toURI())` for proper URI handling
- [ ] MockMvc `post(...)` uses `.contentType(MediaType.APPLICATION_XML)` matching controller's `@PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})`
- [ ] Cleanup in `@BeforeEach` deletes from `outbox_events` first (FK constraint), then `incoming_invoices` — matches existing pattern in `AbstractCdcIT`
