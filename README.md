# Document Intake Service

Gateway microservice for receiving and validating Thai e-Tax XML documents. Part of the Thai e-Tax Invoice processing pipeline.

## Overview

- **Port**: 8081
- **Database**: PostgreSQL (`intake_db`)
- **Tech Stack**: Java 21, Spring Boot 3.2.5, Apache Camel, Kafka, Debezium CDC
- **Package**: `com.wpanther.document.intake`

## Features

- Three-layer XML validation (well-formedness, XSD schema, Schematron rules)
- Support for 6 Thai e-Tax document types
- Outbox pattern with Debezium CDC for reliable event delivery
- REST API and Kafka consumer for document intake

## Architecture

### Document Flow

```
External System (REST/Kafka)
    ↓
DocumentIntakeService.submitInvoice()
    ↓
┌─────────────────────────────────────────┐
│  Transaction Boundary                   │
│  1. Save IncomingDocument (RECEIVED)    │
│  2. Write trace event to outbox         │
│  3. Validate XML (XSD + Schematron)     │
│  4. If valid: Write StartSagaCommand    │
│  5. Update status to FORWARDED          │
└─────────────────────────────────────────┘
    ↓
Debezium CDC (reads outbox_events)
    ↓
Kafka Topics:
  - saga.commands.orchestrator
  - trace.document.received
```

### DDD Package Structure (Hexagonal Architecture)

```
com.wpanther.document.intake/
├── domain/                          # Core business logic (framework-independent)
│   ├── model/                       # IncomingDocument, ValidationResult, DocumentStatus
│   ├── repository/                   # Repository interfaces
│   └── exception/                    # Domain exceptions
│
├── application/                      # Use cases and orchestration
│   ├── usecase/                      # Use case interfaces + implementations
│   │   ├── SubmitDocumentUseCase.java
│   │   ├── GetDocumentUseCase.java
│   │   └── DocumentIntakeApplicationService.java
│   ├── port/out/                     # Outbound ports
│   │   ├── XmlValidationPort.java
│   │   ├── DocumentEventPublisher.java
│   │   └── DocumentIntakeMetricsPort.java
│   └── dto/event/                    # Kafka wire DTOs
│       ├── StartSagaCommand.java
│       ├── DocumentReceivedTraceEvent.java
│       └── EventStatus.java
│
├── infrastructure/                   # Framework implementations
│   └── adapter/
│       ├── in/web/                  # REST API controller
│       └── out/
│           ├── persistence/         # JPA entities and repositories
│           │   └── outbox/          # Outbox pattern implementation
│           ├── validation/          # XML validation with teda library
│           ├── messaging/            # EventPublisher
│           ├── metrics/             # Micrometer metrics
│           └── health/              # Health indicators
│
└── infrastructure/config/            # Configuration (by concern)
    ├── camel/                        # Apache Camel routes
    ├── openapi/                      # OpenAPI/Swagger config
    ├── outbox/                      # Outbox configuration
    ├── ratelimit/                   # Rate limiting config
    ├── security/                   # Security config
    └── validation/                 # Validation properties
```

### Document State Machine

```
RECEIVED → VALIDATING → VALIDATED → FORWARDED
                      ↘ INVALID
                      ↘ FAILED
```

## Supported Document Types

| Type | Namespace |
|------|-----------|
| TAX_INVOICE | `urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2` |
| RECEIPT | `urn:etda:uncefact:data:standard:Receipt_CrossIndustryInvoice:2` |
| INVOICE | `urn:etda:uncefact:data:standard:Invoice_CrossIndustryInvoice:2` |
| DEBIT_CREDIT_NOTE | `urn:etda:uncefact:data:standard:DebitCreditNote_CrossIndustryInvoice:2` |
| CANCELLATION_NOTE | `urn:etda:uncefact:data:standard:CancellationNote_CrossIndustryInvoice:2` |
| ABBREVIATED_TAX_INVOICE | `urn:etda:uncefact:data:standard:AbbreviatedTaxInvoice_CrossIndustryInvoice:2` |

Document type is auto-detected from XML namespace URI.

## Kafka Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `document.intake` | Consumer | Receive documents from external systems |
| `saga.commands.orchestrator` | Producer (via CDC) | Start saga for validated documents |
| `trace.document.received` | Producer (via CDC) | Trace document lifecycle |
| `document.intake.dlq` | Producer | Dead letter queue |

## Quick Start

### Prerequisites

1. Install dependencies:
   ```bash
   # From repository root
   cd teda && mvn clean install
   cd ../saga-commons && mvn clean install
   ```

2. Start infrastructure:
   - PostgreSQL on `localhost:5432` (database: `intake_db`)
   - Kafka on `localhost:9092`
   - Debezium Kafka Connect on `localhost:8083`

### Build and Run

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run

# Run unit tests (fast, no external dependencies)
mvn test

# Run integration tests (requires external containers)
mvn verify

# Database migrations
mvn flyway:migrate
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `intake_db` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka server URL |

## REST API

### Submit Document

```bash
curl -X POST http://localhost:8081/api/v1/invoices \
  -H "Content-Type: application/xml" \
  -H "X-Correlation-ID: $(uuidgen)" \
  -d @document.xml
```

**Response**: `202 Accepted` with correlation ID

### Get Document Status

```bash
curl http://localhost:8081/api/v1/invoices/{id}
```

### Test Organization

| Directory | Test Type | Command | External Dependencies |
|-----------|-----------|---------|----------------------|
| `src/test/java` | Unit Tests | `mvn test` | None |
| `src/test/java` | CDC Integration Tests (`*IT.java`) | `mvn verify -P integration` | PostgreSQL, Kafka, Debezium |

### Unit Tests

Fast, isolated tests that don't require external containers.

```bash
# Run all unit tests
mvn test

# Run specific test class
mvn test -Dtest=XmlValidationServiceImplTest

# Run specific test method
mvn test -Dtest=DocumentIntakeServiceTest#testSubmitInvoiceWithValidXml
```

**Result**: Runs 362 unit tests (~20-30 seconds).

### Integration Tests

Full end-to-end tests requiring external infrastructure.

**Prerequisites**: Start external containers first:

```bash
# From invoice-microservices root
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```

**Container Ports**:
- PostgreSQL: `localhost:5433`
- Kafka: `localhost:9093`
- Debezium: `localhost:8083`

**Run Integration Tests**:

```bash
mvn verify
```

**Stop Containers**:

```bash
# From invoice-microservices root
./scripts/test-containers-stop.sh
```

### Test Profiles

| Profile | Database | Maven Phase | Tests |
|---------|----------|-------------|--------|
| `test` | H2 (in-memory) | `test` (unit tests) |
| `cdc-test` | PostgreSQL (external) | `integration-test` (CDC tests) |

### Test Files

| Directory | Contents | Used By |
|-----------|----------|---------|
| `src/test/resources/samples/valid/` | Valid Thai e-Tax XML documents | Unit tests |
| `src/test/resources/samples/invalid/` | Invalid documents for testing validation | Unit tests |
| `src/test/resources/application-cdc-test.yml` | CDC integration test configuration | Integration tests (`*IT.java`) |

| `src/test/resources/samples/invalid/` | Invalid documents for testing validation |

## Outbox Pattern

This service uses the outbox pattern with Debezium CDC for reliable event delivery:

1. **Transaction**: Domain state + outbox event saved atomically
2. **CDC**: Debezium reads `outbox_events` table via PostgreSQL logical replication
3. **Routing**: EventRouter transforms routes events to Kafka topics based on `topic` field

### Outbox Table Schema

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(255),
    partition_key VARCHAR(255),
    payload TEXT NOT NULL,
    headers TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL
);
```

### Debezium Connector

Connector name: `outbox-connector-intake`

Key configuration:
- `table.include.list`: `public.outbox_events`
- `transforms.outbox.type`: `io.debezium.transforms.outbox.EventRouter`
- `transforms.outbox.route.by.field`: `topic`

## Troubleshooting

### JAXB Unmarshaling Fails

- Ensure teda library is installed: `cd teda && mvn clean install`
- JAXB contexts use `.impl` packages (not interface packages)

### Events Not Published to Kafka

1. Check Debezium connector status:
   ```bash
   curl http://localhost:8083/connectors/outbox-connector-intake/status
   ```

2. Verify outbox entries exist:
   ```bash
   psql -d intake_db -c "SELECT * FROM outbox_events ORDER BY created_at DESC LIMIT 5"
   ```

3. Check PostgreSQL `wal_level=logical`

### Database Connection Errors

- Verify PostgreSQL is running: `psql -h localhost -U postgres -d intake_db`
- Check Flyway migrations: `mvn flyway:info`

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| teda (thai-etax-invoice) | 1.0.0 | JAXB classes, XSD schemas, Schematron rules |
| saga-commons | 1.0.0-SNAPSHOT | Outbox pattern support |
| Apache Camel | 4.14.4 | Message routing |
| Spring Boot | 3.2.5 | Application framework |

## Related Services

| Service | Port | Relationship |
|---------|------|--------------|
| orchestrator-service | 8100 | Consumes `saga.commands.orchestrator` |
| notification-service | 8099 | Consumes `trace.document.received` |
| invoice-processing-service | 8082 | Processes INVOICE documents |
| taxinvoice-processing-service | 8083 | Processes TAX_INVOICE documents |