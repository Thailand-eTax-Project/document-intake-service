# Integration Test Layout Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate CDC integration tests from the uncompiled `src/it/java` directory to standard Maven `src/test/java` using `*IT.java` naming, and restore `CamelConfigTest` to the normal test run.

**Architecture:** All tests live in `src/test/java`. Surefire runs `*Test.java` always; Failsafe runs `*IT.java` only under `-P integration`. The `src/it/` directory is deleted. No extra Maven plugins required.

**Tech Stack:** Java 21, Maven Surefire 3.1.2, Maven Failsafe 3.1.2, Spring Boot 3.2.5, JUnit 5, Awaitility 4.2.0

---

## File Map

| Action | File |
|--------|------|
| Modify | `pom.xml` |
| Create | `src/test/java/com/wpanther/document/intake/integration/config/CdcTestConfiguration.java` |
| Create | `src/test/java/com/wpanther/document/intake/integration/config/TestKafkaConsumerConfig.java` |
| Create | `src/test/resources/application-cdc-test.yml` |
| Create | `src/test/java/com/wpanther/document/intake/integration/AbstractCdcIT.java` |
| Create | `src/test/java/com/wpanther/document/intake/integration/OutboxTableIT.java` |
| Create | `src/test/java/com/wpanther/document/intake/integration/DocumentIntakeCdcIT.java` |
| Rename+Edit | `src/test/java/.../config/camel/CamelConfigIntegrationTest.java` → `CamelConfigTest.java` |
| Delete | `src/it/` directory |
| Modify | `CLAUDE.md` |

---

## Task 1: Fix pom.xml — surefire and failsafe

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Remove the surefire IntegrationTest exclusion block**

In `pom.xml`, find the `maven-surefire-plugin` configuration and remove the `<excludes>` block entirely. The plugin should look like:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.1.2</version>
</plugin>
```

- [ ] **Step 2: Simplify the failsafe plugin configuration**

Find the `maven-failsafe-plugin` configuration and replace with:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.1.2</version>
    <executions>
        <execution>
            <id>integration-test</id>
            <phase>integration-test</phase>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <skip>${skipITs}</skip>
        <includes>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```

The `<testSourceDirectory>` line is removed — `src/test/java` is the Maven default and no longer needs to be stated explicitly.

- [ ] **Step 3: Verify pom.xml compiles**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/document-intake-service
mvn test-compile -q
```

Expected: `BUILD SUCCESS` (no output, no errors).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: fix surefire/failsafe — use *IT naming, remove src/it testSourceDirectory"
```

---

## Task 2: Move CDC config classes to src/test/java

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/config/CdcTestConfiguration.java`
- Create: `src/test/java/com/wpanther/document/intake/integration/config/TestKafkaConsumerConfig.java`

- [ ] **Step 1: Create CdcTestConfiguration**

Create `src/test/java/com/wpanther/document/intake/integration/config/CdcTestConfiguration.java` with this content (package declaration unchanged — it maps to the same package regardless of source root):

```java
package com.wpanther.document.intake.integration.config;

import org.apache.camel.spring.boot.CamelAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test configuration for CDC integration tests.
 * <p>
 * Excludes Camel auto-configuration to prevent Kafka consumers from starting.
 * The tests verify CDC flow by consuming from Kafka directly.
 */
@Configuration
@EnableAutoConfiguration(exclude = {
    CamelAutoConfiguration.class,
    KafkaAutoConfiguration.class
})
@Import(TestKafkaConsumerConfig.class)
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
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*CamelConfig.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Controller.*")
    }
)
public class CdcTestConfiguration {
}
```

- [ ] **Step 2: Create TestKafkaConsumerConfig**

Create `src/test/java/com/wpanther/document/intake/integration/config/TestKafkaConsumerConfig.java`:

```java
package com.wpanther.document.intake.integration.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * Kafka configuration for CDC integration tests.
 * Provides consumers to verify messages published via Debezium CDC.
 */
@Configuration
public class TestKafkaConsumerConfig {

    @Value("${app.kafka.bootstrap-servers:localhost:9093}")
    private String bootstrapServers;

    @Bean
    public Properties kafkaAdminProperties() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        return props;
    }

    @Bean
    public KafkaConsumer<String, String> testKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cdc-test-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    /**
     * Create Kafka topics needed for tests.
     * Called from AbstractCdcIT.setupInfrastructure().
     */
    public void createTopics() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdminProperties())) {
            List<NewTopic> topics = List.of(
                new NewTopic("saga.commands.orchestrator", 1, (short) 1),
                new NewTopic("trace.document.received", 1, (short) 1)
            );
            adminClient.createTopics(topics).all().get();
        } catch (Exception e) {
            // Topics may already exist - ignore
        }
    }
}
```

- [ ] **Step 3: Verify test-compile picks up the new classes**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wpanther/document/intake/integration/config/
git commit -m "test: move CDC config classes from src/it to src/test/java"
```

---

## Task 3: Move application-cdc-test.yml to src/test/resources

**Files:**
- Create: `src/test/resources/application-cdc-test.yml`

- [ ] **Step 1: Create the file**

Create `src/test/resources/application-cdc-test.yml` with this content (identical to current `src/it/resources/application-cdc-test.yml`):

```yaml
server:
  port: 0  # Random port for tests

spring:
  application:
    name: document-intake-service-cdc-test

  main:
    allow-bean-definition-overriding: true

  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5433}/${DB_NAME:intake_db}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5

  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    show-sql: true

  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration

# Disable Camel auto-start (we don't want Kafka consumers running)
camel:
  springboot:
    main-run-controller: false

# Application config
app:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9093}
    topics:
      invoice-intake: document.intake
      intake-dlq: document.intake.dlq
      tax-invoice: document.received.tax-invoice
      receipt: document.received.receipt
      invoice: document.received.invoice
      debit-credit-note: document.received.debit-credit-note
      cancellation: document.received.cancellation
      abbreviated: document.received.abbreviated
      saga-commands-orchestrator: saga.commands.orchestrator
      trace-document-received: trace.document.received

# Disable Eureka
eureka:
  client:
    enabled: false

# Actuator endpoints
management:
  endpoints:
    enabled-by-default: false
    web:
      exposure:
        include: health

# Logging
logging:
  level:
    root: WARN
    com.wpanther.document.intake: DEBUG
    org.apache.kafka: WARN
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/application-cdc-test.yml
git commit -m "test: move application-cdc-test.yml from src/it/resources to src/test/resources"
```

---

## Task 4: Create AbstractCdcIT

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/AbstractCdcIT.java`

- [ ] **Step 1: Create the file**

Create `src/test/java/com/wpanther/document/intake/integration/AbstractCdcIT.java`:

```java
package com.wpanther.document.intake.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.document.intake.integration.config.CdcTestConfiguration;
import com.wpanther.document.intake.integration.config.TestKafkaConsumerConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for CDC integration tests.
 * <p>
 * Prerequisites:
 *   1. Start containers: ./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
 *   2. Containers must be running:
 *      - PostgreSQL: localhost:5433
 *      - Kafka: localhost:9093
 *      - Debezium: localhost:8083
 */
@SpringBootTest(
    classes = CdcTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("cdc-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractCdcIT {

    protected static final String POSTGRES_HOST = "localhost";
    protected static final int POSTGRES_PORT = 5433;
    protected static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9093";
    protected static final String DEBEZIUM_URL = "http://localhost:8083";
    protected static final String DEBEZIUM_CONNECTOR_NAME = "outbox-connector-intake";

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected KafkaConsumer<String, String> testKafkaConsumer;

    @Autowired
    protected TestKafkaConsumerConfig kafkaConfig;

    protected HttpClient httpClient = HttpClient.newHttpClient();

    // Cache for received Kafka messages (topic -> list of records)
    protected Map<String, List<ConsumerRecord<String, String>>> receivedMessages = new ConcurrentHashMap<>();

    @BeforeAll
    void setupInfrastructure() throws Exception {
        verifyExternalContainers();
        verifyDebeziumConnectorRunning();
        kafkaConfig.createTopics();
        subscribeToTopics();
    }

    @BeforeEach
    void cleanupTestData() {
        // Clean tables in correct order (foreign key constraints)
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM incoming_invoices");
        receivedMessages.clear();
    }

    /**
     * Verify that external containers are accessible.
     */
    private void verifyExternalContainers() {
        try {
            // Test PostgreSQL connection
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            assertThat(result).isEqualTo(1);

            // Test Kafka connection
            Properties props = new Properties();
            props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
            try (AdminClient adminClient = AdminClient.create(props)) {
                adminClient.listTopics().names().get();
            }

            // Test Debezium connection
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEBEZIUM_URL + "/connectors"))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    /**
     * Verify that Debezium connector is running.
     * Waits up to 2 minutes for connector to be RUNNING.
     */
    private void verifyDebeziumConnectorRunning() {
        await().atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofSeconds(5))
            .until(() -> isConnectorRunning(DEBEZIUM_CONNECTOR_NAME));
    }

    /**
     * Check if a Debezium connector is in RUNNING state.
     */
    protected boolean isConnectorRunning(String connectorName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEBEZIUM_URL + "/connectors/" + connectorName + "/status"))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            return response.body().contains("\"state\":\"RUNNING\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Subscribe to Kafka topics for message verification.
     */
    private void subscribeToTopics() {
        testKafkaConsumer.subscribe(List.of(
            "saga.commands.orchestrator",
            "trace.document.received"
        ));
    }

    /**
     * Poll Kafka for messages and cache them.
     */
    protected void pollKafkaMessages() {
        ConsumerRecords<String, String> records = testKafkaConsumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
            receivedMessages.computeIfAbsent(record.topic(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
        }
    }

    /**
     * Check if a message with given partition key exists on topic.
     */
    protected boolean hasMessageOnTopic(String topic, String partitionKey) {
        pollKafkaMessages();
        List<ConsumerRecord<String, String>> messages = receivedMessages.get(topic);
        if (messages == null) return false;
        return messages.stream().anyMatch(r -> partitionKey.equals(r.key()));
    }

    /**
     * Get messages from topic matching partition key.
     */
    protected List<ConsumerRecord<String, String>> getMessagesFromTopic(String topic, String partitionKey) {
        pollKafkaMessages();
        List<ConsumerRecord<String, String>> messages = receivedMessages.get(topic);
        if (messages == null) return Collections.emptyList();
        return messages.stream()
            .filter(r -> partitionKey.equals(r.key()))
            .toList();
    }

    /**
     * Parse JSON from Kafka message value.
     */
    protected JsonNode parseJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    /**
     * Load test XML from resources.
     */
    protected String loadTestXml(String filename) throws IOException {
        Path path = Path.of(getClass().getClassLoader()
            .getResource("samples/valid/" + filename).getPath());
        return Files.readString(path);
    }

    /**
     * Awaitility helper with default configuration.
     */
    protected ConditionFactory await() {
        return Awaitility.await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofSeconds(2));
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/document/intake/integration/AbstractCdcIT.java
git commit -m "test: add AbstractCdcIT (renamed from AbstractCdcIntegrationTest)"
```

---

## Task 5: Create OutboxTableIT

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/OutboxTableIT.java`

- [ ] **Step 1: Create the file**

Create `src/test/java/com/wpanther/document/intake/integration/OutboxTableIT.java`:

```java
package com.wpanther.document.intake.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for outbox table schema.
 * Verifies database structure without requiring Kafka/Debezium.
 */
@DisplayName("Outbox Table Schema Tests")
class OutboxTableIT extends AbstractCdcIT {

    @Test
    @DisplayName("Should have outbox_events table")
    void shouldHaveOutboxEventsTable() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'outbox_events'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Should have incoming_invoices table")
    void shouldHaveIncomingInvoicesTable() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'incoming_invoices'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Should have Debezium routing columns in outbox_events")
    void shouldHaveDebeziumRoutingColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'outbox_events' " +
            "AND column_name IN ('topic', 'partition_key', 'headers')");

        List<String> columnNames = columns.stream()
            .map(c -> (String) c.get("column_name"))
            .toList();

        assertThat(columnNames).containsExactlyInAnyOrder("topic", "partition_key", "headers");
    }

    @Test
    @DisplayName("Should have saga-commons columns in outbox_events")
    void shouldHaveSagaCommonsColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'outbox_events' " +
            "AND column_name IN ('retry_count', 'error_message', 'published_at')");

        List<String> columnNames = columns.stream()
            .map(c -> (String) c.get("column_name"))
            .toList();

        assertThat(columnNames).containsExactlyInAnyOrder("retry_count", "error_message", "published_at");
    }

    @Test
    @DisplayName("Should have status index for Debezium polling")
    void shouldHaveStatusIndex() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_events' AND indexname LIKE '%status%'");

        assertThat(indexes).isNotEmpty();
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/document/intake/integration/OutboxTableIT.java
git commit -m "test: add OutboxTableIT (renamed from OutboxTableIntegrationTest)"
```

---

## Task 6: Create DocumentIntakeCdcIT

**Files:**
- Create: `src/test/java/com/wpanther/document/intake/integration/DocumentIntakeCdcIT.java`

- [ ] **Step 1: Create the file**

Create `src/test/java/com/wpanther/document/intake/integration/DocumentIntakeCdcIT.java`:

```java
package com.wpanther.document.intake.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.model.DocumentStatus;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full CDC integration tests for document intake service.
 * Verifies the complete flow: Document -> Database -> Outbox -> Debezium CDC -> Kafka.
 */
@DisplayName("Document Intake CDC Integration Tests")
class DocumentIntakeCdcIT extends AbstractCdcIT {

    @Autowired
    private SubmitDocumentUseCase documentIntakeService;

    @Nested
    @DisplayName("Database Write Tests")
    class DatabaseWriteTests {

        @Test
        @DisplayName("Should save incoming document to database")
        void shouldSaveIncomingDocumentToDatabase() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then
            assertThat(document.getId()).isNotNull();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.FORWARDED);

            // Verify in database using JDBC (not JPA) to avoid session cache
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM incoming_invoices WHERE id = ?::uuid",
                document.getId().toString());

            assertThat(row.get("status")).isEqualTo("FORWARDED");
            assertThat(row.get("document_type")).isEqualTo("TAX_INVOICE");
        }

        @Test
        @DisplayName("Should create outbox events in same transaction")
        void shouldCreateOutboxEventsInSameTransaction() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then - verify outbox entries exist
            List<Map<String, Object>> outboxEvents = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                document.getId().toString());

            // Should have multiple events: RECEIVED trace, VALIDATED trace, StartSagaCommand, FORWARDED trace
            assertThat(outboxEvents).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Outbox Pattern Tests")
    class OutboxPatternTests {

        @Test
        @DisplayName("Should write StartSagaCommand with correct topic")
        void shouldWriteStartSagaCommandWithCorrectTopic() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then
            Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? AND event_type = 'StartSagaCommand'",
                document.getId().toString());

            assertThat(outbox.get("topic")).isEqualTo("saga.commands.orchestrator");
            assertThat(outbox.get("partition_key")).isEqualTo(correlationId);
            assertThat(outbox.get("aggregate_type")).isEqualTo("IncomingDocument");
            assertThat(outbox.get("status")).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("Should write trace events with correct topic")
        void shouldWriteTraceEventsWithCorrectTopic() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then
            List<Map<String, Object>> traceEvents = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? AND event_type = 'DocumentReceivedTraceEvent'",
                document.getId().toString());

            assertThat(traceEvents).isNotEmpty();
            for (Map<String, Object> event : traceEvents) {
                assertThat(event.get("topic")).isEqualTo("trace.document.received");
                assertThat(event.get("partition_key")).isEqualTo(correlationId);
            }
        }

        @Test
        @DisplayName("Should set correct partition key for ordering")
        void shouldSetCorrectPartitionKeyForOrdering() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then - all events for same document should have same partition key
            List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT DISTINCT partition_key FROM outbox_events WHERE partition_key = ?",
                correlationId);

            assertThat(events).hasSize(1);
        }
    }

    @Nested
    @DisplayName("CDC Flow Tests")
    class CdcFlowTests {

        @Test
        @DisplayName("Should publish StartSagaCommand to Kafka topic via CDC")
        void shouldPublishStartSagaCommandToKafkaTopic() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When - submit document
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);
            String documentId = document.getId().toString();

            // Then - wait for Kafka message (CDC takes time)
            await().until(() -> hasMessageOnTopic("saga.commands.orchestrator", correlationId));

            // Verify message content
            List<ConsumerRecord<String, String>> messages = getMessagesFromTopic("saga.commands.orchestrator", correlationId);
            assertThat(messages).isNotEmpty();

            JsonNode payload = parseJson(messages.get(0).value());
            assertThat(payload.get("documentId").asText()).isEqualTo(documentId);
            assertThat(payload.get("documentType").asText()).isEqualTo("TAX_INVOICE");
            assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should publish trace events to Kafka topic via CDC")
        void shouldPublishTraceEventsToKafkaTopic() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then - wait for trace events on Kafka
            await().until(() -> hasMessageOnTopic("trace.document.received", correlationId));

            List<ConsumerRecord<String, String>> messages = getMessagesFromTopic("trace.document.received", correlationId);
            assertThat(messages).isNotEmpty();

            // Should have multiple trace events (RECEIVED, VALIDATED, FORWARDED)
            assertThat(messages.size()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should preserve correlation ID through CDC flow")
        void shouldPreserveCorrelationIdThroughCdcFlow() throws Exception {
            // Given
            String xml = loadTestXml("TaxInvoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then - wait and verify correlation ID in Kafka message
            await().until(() -> hasMessageOnTopic("saga.commands.orchestrator", correlationId));

            List<ConsumerRecord<String, String>> messages = getMessagesFromTopic("saga.commands.orchestrator", correlationId);
            ConsumerRecord<String, String> message = messages.get(0);

            // Kafka message key should be the correlation ID (partition key)
            assertThat(message.key()).isEqualTo(correlationId);

            // Payload should also contain correlation ID
            JsonNode payload = parseJson(message.value());
            assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should not publish StartSagaCommand for invalid document")
        void shouldNotPublishStartSagaCommandForInvalidDocument() throws Exception {
            // Given - malformed XML
            String invalidXml = "<invalid>not a valid e-tax document</invalid>";
            String correlationId = UUID.randomUUID().toString();

            // When/Then - should throw exception
            try {
                documentIntakeService.submitDocument(invalidXml, "API", correlationId);
            } catch (Exception e) {
                // Expected - invalid document
            }

            // Verify no StartSagaCommand was written
            List<Map<String, Object>> sagaCommands = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE event_type = 'StartSagaCommand' AND partition_key = ?",
                correlationId);

            assertThat(sagaCommands).isEmpty();
        }
    }

    @Nested
    @DisplayName("Document Type Tests")
    class DocumentTypeTests {

        @Test
        @DisplayName("Should handle Invoice document type")
        void shouldHandleInvoiceDocumentType() throws Exception {
            // Given
            String xml = loadTestXml("Invoice_2p1_valid.xml");
            String correlationId = UUID.randomUUID().toString();

            // When
            IncomingDocument document = documentIntakeService.submitDocument(xml, "API", correlationId);

            // Then
            assertThat(document.getDocumentType().name()).isEqualTo("INVOICE");

            // Verify in outbox
            Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? AND event_type = 'StartSagaCommand'",
                document.getId().toString());

            String payload = (String) outbox.get("payload");
            JsonNode payloadJson = parseJson(payload);
            assertThat(payloadJson.get("documentType").asText()).isEqualTo("INVOICE");
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/document/intake/integration/DocumentIntakeCdcIT.java
git commit -m "test: add DocumentIntakeCdcIT (renamed from DocumentIntakeCdcIntegrationTest)"
```

---

## Task 7: Fix CamelConfigTest — rename and re-enable

**Files:**
- Modify: `src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigIntegrationTest.java` → rename to `CamelConfigTest.java`

- [ ] **Step 1: Rename the file**

```bash
mv src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigIntegrationTest.java \
   src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigTest.java
```

- [ ] **Step 2: Update class name and remove @Disabled**

Open `CamelConfigTest.java` and make two changes:
1. Change the class declaration from `class CamelConfigIntegrationTest` to `class CamelConfigTest`
2. Remove `@Disabled("Camel integration tests require Kafka infrastructure - coverage will be provided via other integration tests")` and its import (`import org.junit.jupiter.api.Disabled;`)

The result should look like this at the top of the file:

```java
/**
 * Integration tests for CamelConfig route configuration.
 * Tests that routes are properly configured and execute the expected flow.
 */
@CamelSpringBootTest
@EnableAutoConfiguration(exclude = {
    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.kafka.topics.document-intake=document.intake",
    "app.kafka.topics.intake-dlq=document.intake.dlq",
    "app.kafka.topics.tax-document=document.received.tax-document",
    "app.kafka.topics.receipt=document.received.receipt",
    "app.kafka.topics.document=document.received.document",
    "app.kafka.topics.debit-credit-note=document.received.debit-credit-note",
    "app.kafka.topics.cancellation=document.received.cancellation",
    "app.kafka.topics.abbreviated=document.received.abbreviated",
    "app.kafka.bootstrap-servers=localhost:9092"
})
@DisplayName("CamelConfig Integration Tests")
class CamelConfigTest {
```

- [ ] **Step 3: Run the test to confirm it passes**

```bash
mvn clean test -Dtest=CamelConfigTest -q
```

Expected: `BUILD SUCCESS` — all tests in the class pass (they use mocked `SubmitDocumentUseCase`, no real Kafka/DB).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigTest.java
git rm src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigIntegrationTest.java
git commit -m "test: rename CamelConfigIntegrationTest to CamelConfigTest, remove @Disabled"
```

---

## Task 8: Delete src/it directory

**Files:**
- Delete: `src/it/` (entire directory)

- [ ] **Step 1: Delete the directory**

```bash
rm -rf src/it
```

- [ ] **Step 2: Verify normal test suite still passes**

```bash
mvn clean test -q
```

Expected: `BUILD SUCCESS`. All tests in `src/test/java` pass, including the newly re-enabled `CamelConfigTest`.

- [ ] **Step 3: Commit**

```bash
git rm -r src/it/
git commit -m "chore: remove src/it directory — CDC tests now live in src/test/java as *IT.java"
```

---

## Task 9: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update CDC integration test references**

In `CLAUDE.md`, find the CDC integration test section. Update all class names and paths:

| Old                                                                     | New |
|-------------------------------------------------------------------------|-----|
| `AbstractCdcIntegrationTest`                                            | `AbstractCdcIT` |
| `OutboxTableIntegrationTest`                                            | `OutboxTableIT` |
| `DocumentIntakeCdcIntegrationTest`                                      | `DocumentIntakeCdcIT` |
| `src/test/resources/application-cdc-test.yml` (incorrect reference)     | `src/test/resources/application-cdc-test.yml` (already correct after move) |
| `src/it/resources/application-cdc-test.yml`                             | `src/test/resources/application-cdc-test.yml` |
| `*CdcIntegrationTest,*TableIntegrationTest` (in mvn clean test command) | `*CdcIT,*TableIT` |

The `mvn test` command in CLAUDE.md for CDC tests should become:
```bash
mvn clean test -Dtest="*CdcIT,*TableIT" -Dspring.profiles.active=cdc-test
```

- [ ] **Step 2: Verify the run command in CLAUDE.md is consistent with the new naming**

The full CDC test run block should read:

```bash
# Run CDC integration tests (requires external containers)
# First start containers:
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
# Then run tests:
cd services/document-intake-service
mvn clean verify -P integration
# Stop containers when done:
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-stop.sh
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md CDC test class names and resource paths"
```

---

## Task 10: Final verification

- [ ] **Step 1: Confirm normal tests pass (no CDC infra needed)**

```bash
mvn clean test -q
```

Expected: `BUILD SUCCESS`. No `*IT.java` tests run (they're skipped by surefire because they don't match `*Test.java`).

- [ ] **Step 2: Confirm failsafe would pick up IT tests (dry run)**

```bash
mvn clean failsafe:integration-test -DskipITs=false --dry-run 2>&1 | grep -i "IT"
```

Expected: Output includes `DocumentIntakeCdcIT`, `OutboxTableIT` in the scan.

- [ ] **Step 3: Confirm no src/it directory remains**

```bash
ls src/
```

Expected: Only `main/` and `test/` directories listed.
