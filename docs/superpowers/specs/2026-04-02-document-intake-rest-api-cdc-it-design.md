# Document Intake REST API CDC Integration Tests — Design

## Context

The `document-intake-service` has CDC integration tests in `DocumentIntakeCdcIT` that call the `SubmitDocumentUseCase` service layer directly, bypassing the REST controller. This misses coverage of the full HTTP request lifecycle (serialization, REST routing, response mapping).

Two integration tests are needed that:
1. POST valid Tax Invoice XML to the REST API endpoint
2. POST valid Invoice XML to the REST API endpoint
3. Verify `StartSagaCommand` messages appear on `saga.commands.orchestrator` Kafka topic via Debezium CDC

## Architecture

### New Test Class

`src/test/java/com/wpanther/document/intake/integration/DocumentIntakeRestApiCdcIT.java`

Extends `AbstractCdcIT` (already has container verification, Kafka polling, JDBC template, and `loadTestXml()`).

### Test Configuration

Create a new `@Configuration` class `RestApiCdcTestConfiguration` that:
- Extends `CdcTestConfiguration` to inherit all CDC infrastructure beans
- **Adds** `DocumentIntakeController` back into the component scan (it is currently excluded via regex)
- Enables web environment with `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)`
- Enables security auto-configuration with `app.security.enabled=false` (no OAuth2/Eureka needed in tests)

### REST Endpoint Under Test

`POST /api/v1/documents` — accepts `application/xml` or `text/xml`, returns 202 Accepted with correlation ID.

### Test Cases

| # | Description | XML Sample | Expected CDC |
|---|-------------|-----------|-------------|
| 1 | Submit valid Tax Invoice via REST API | `TaxInvoice_2p1_valid.xml` | `StartSagaCommand` with `documentType=TAX_INVOICE` on `saga.commands.orchestrator` |
| 2 | Submit valid Invoice via REST API | `Invoice_2p1_valid.xml` | `StartSagaCommand` with `documentType=INVOICE` on `saga.commands.orchestrator` |

Each test:
1. Loads XML from `src/test/resources/samples/valid/`
2. POSTs to `/api/v1/documents` with `X-Correlation-ID` header
3. Asserts HTTP 202 Accepted and extracts `correlationId` from response JSON
4. Awaits (up to 2 min) for message on `saga.commands.orchestrator` with matching partition key
5. Parses message payload and asserts `documentType` field matches expected value
6. Optionally verifies database state (`incoming_invoices.status = FORWARDED`)

### Container Prerequisites (unchanged)

```
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```
- PostgreSQL: `localhost:5433`
- Kafka: `localhost:9093`
- Debezium Connect: `localhost:8083`

### Maven Profile

Tests use `integration` profile (same as existing `DocumentIntakeCdcIT`):
```bash
mvn verify -P integration -Dtest="DocumentIntakeRestApiCdcIT" \
  -Dspring.profiles.active=cdc-test
```

### No New Files Beyond the Test Class

Reuse existing infrastructure:
- `AbstractCdcIT` — base class
- `CdcTestConfiguration` — infrastructure beans
- `src/test/resources/samples/valid/TaxInvoice_2p1_valid.xml` — Tax Invoice sample
- `src/test/resources/samples/valid/Invoice_2p1_valid.xml` — Invoice sample
- `application-cdc-test.yml` — existing test profile

Only one new file: `DocumentIntakeRestApiCdcIT.java`

### Key Differences from Existing `DocumentIntakeCdcIT`

| Aspect | Existing `DocumentIntakeCdcIT` | New `DocumentIntakeRestApiCdcIT` |
|--------|-------------------------------|----------------------------------|
| Web layer | NONE (no HTTP) | RANDOM_PORT (real HTTP) |
| Entry point | `SubmitDocumentUseCase.submitDocument()` | `WebTestClient.post()` |
| Controller excluded | Yes (regex filter) | No (explicitly included) |
| Tests HTTP status | No | Yes (202 Accepted) |
| Tests response body | No | Yes (correlationId in JSON) |

## Verification

1. Start containers: `./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors`
2. Run test: `mvn verify -P integration -Dtest="DocumentIntakeRestApiCdcIT" -Dspring.profiles.active=cdc-test`
3. Assert: Both tests pass, Kafka topic `saga.commands.orchestrator` receives correct messages
