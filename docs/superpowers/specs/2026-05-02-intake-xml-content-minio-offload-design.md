# Intake XML Content MinIO Offload Design

**Date:** 2026-05-02
**Service:** document-intake-service
**Status:** Approved

## Problem

`document-intake-service` stores raw XML payloads in two places:

1. PostgreSQL column `incoming_invoices.xml_content TEXT NOT NULL`
2. The `xmlContent` field of `StartSagaCommand`, which Debezium ships through `saga.commands.orchestrator` to `orchestrator-service` — and from there into ~7 downstream `saga.command.*` topics.

For Thai e-Tax invoice XML this can be tens to hundreds of KB per document. The result is large DB rows, large outbox payloads, large Kafka messages, and full duplication of the same blob across PostgreSQL plus every saga step that forwards it.

## Goal

Move the XML payload into MinIO (S3-compatible object store) once, at intake. Replace every inline copy with a single S3 URI reference (`xmlContentUrl` in the aggregate, the DB column, and the saga command — single name across all layers, no translation in mappers). Validation, the document state machine, the outbox pattern, and the trace event flow are unchanged.

## Scope

**In scope (this spec — sub-project A):**

- `document-intake-service` uploads XML to MinIO before persisting the `IncomingDocument`.
- `incoming_invoices.xml_content` is replaced by `xml_content_url`.
- `StartSagaCommand.xmlContent` is replaced by `xmlContentUrl`.
- New outbound port `XmlStoragePort` + new adapter `MinioXmlStorageAdapter`.
- New configurable bucket via `MINIO_INTAKE_BUCKET` (default `intake-xml`).
- Existing Flyway `V1__baseline_schema.sql` is edited in place (dev-only baseline, per `services/CLAUDE.md`).

**Out of scope (deliberate):**

- `orchestrator-service` and downstream saga consumers still read `xmlContent`. They will fail to deserialize the new payload until updated in a follow-up sub-project (sub-project B). After this spec ships, the pipeline is dev/test only — **do not promote intake past dev until sub-project B lands**.
- Periodic MinIO orphan cleanup job. The existing pattern in `taxinvoice-pdf-generation-service.MinioCleanupService` can be mirrored later if monitoring shows accumulation.
- Data migration of existing rows. V1 is the dev consolidated baseline, so the schema change is destructive and acceptable for dev/test.
- Idempotency / deduplication of repeated submissions of the same XML. Today every submission gets a fresh `documentId`; that behaviour is preserved.

## Decision

### Bucket

Configurable via env var, default `intake-xml`. Separate from the existing `taxinvoices` bucket used by `taxinvoice-pdf-generation-service` because the lifecycle, producer, and consumers are different.

### Object key layout

`YYYY/MM/DD/<documentId>.xml`

- Date-partitioned (matches the PDF service convention).
- Uses `documentId` rather than a fresh UUID, so the key is reproducible from the row alone — useful for debugging and avoids accidental orphans on retry of an *already-saved* document.

### URL format in messages

`s3://<bucket>/<key>`, e.g. `s3://intake-xml/2026/05/02/0c3b6e10-...xml`.

- Endpoint-agnostic — survives MinIO endpoint rotation.
- Encodes both bucket and key, so consumers don't have to assume a bucket name.
- Standard S3 URI scheme; readable by tooling.

### DB schema

Replace `xml_content TEXT NOT NULL` with `xml_content_url VARCHAR(512) NOT NULL` (single source of truth: the MinIO object). Edit `V1__baseline_schema.sql` in place; do not introduce V2.

### Upload timing

Upload to MinIO **before** the `@Transactional` block opens. Order:

1. `xmlContentUrl = xmlStoragePort.store(documentId, xmlContent)` — network I/O, no DB tx.
2. Open `@Transactional`. Inside the tx: save `IncomingDocument(xmlContentUrl=...)`, write all outbox events (RECEIVED trace → VALIDATED trace → [if valid] StartSagaCommand + FORWARDED trace), commit.

Validation still receives the in-memory `xmlContent` from the request — we do **not** re-fetch from MinIO for validation.

This preserves the existing single-transaction outbox guarantee: every outbox event for a document is committed atomically with the document row's terminal state. The cost is that MinIO uploads can outlive a failed tx (orphan); this is rare and accepted (see Risks).

## Components

### New

| Layer | Component | Purpose |
|-------|-----------|---------|
| `application/port/out` | `XmlStoragePort` (interface) | `String store(String documentId, String xmlContent)` returning S3 URI. `void delete(String s3Uri)` for future use. |
| `infrastructure/adapter/out/storage` | `MinioXmlStorageAdapter` | `S3Client`-backed implementation. `@CircuitBreaker(name = "minio")` on `store`/`delete`. Micrometer `Timer`s for `intake.minio.store` and `intake.minio.delete`. Mirrors the pattern in `taxinvoice-pdf-generation-service.MinioStorageAdapter`. |
| `infrastructure/config` | `MinioConfig` | `S3Client` bean from `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_REGION` (path-style access enabled). Mirrors the PDF service config. |

### Modified

| Component | Change |
|-----------|--------|
| `domain/model/IncomingDocument` | Field `xmlContent: String` → `xmlContentUrl: String`. Non-null/non-blank invariant moves to the new field. State machine unchanged. |
| `infrastructure/adapter/out/persistence/IncomingDocumentEntity` | `@Column(name="xml_content")` → `@Column(name="xml_content_url", length=512, nullable=false)`. |
| `infrastructure/adapter/out/persistence/JpaDocumentRepository` | Mapper updated for new field name. |
| `application/dto/event/StartSagaCommand` | Field `xmlContent` → `xmlContentUrl`. Builder, getter, and `@JsonCreator` updated. |
| `infrastructure/adapter/out/messaging/EventPublisher.publishStartSagaCommand` | Pass `xmlContentUrl` instead of `xmlContent`. No other change. |
| `application/usecase/DocumentIntakeApplicationService.submitInvoice` | Call `xmlStoragePort.store(...)` before opening the transaction; pass `xmlContentUrl` into the aggregate; pass the in-memory `xmlContent` to the validator. |
| `src/main/resources/db/migration/V1__baseline_schema.sql` | Replace `xml_content TEXT NOT NULL` with `xml_content_url VARCHAR(512) NOT NULL`. |
| `src/main/resources/application.yml` | Add MinIO client config block + `resilience4j.circuitbreaker.instances.minio.*` (mirroring PDF service). |

## Configuration

| Env var | Default | Purpose |
|---------|---------|---------|
| `MINIO_ENDPOINT` | `http://localhost:9000` | S3 endpoint |
| `MINIO_ACCESS_KEY` | `minioadmin` | S3 access key |
| `MINIO_SECRET_KEY` | `minioadmin` | S3 secret key |
| `MINIO_REGION` | `us-east-1` | S3 region (MinIO ignores but SDK requires) |
| `MINIO_INTAKE_BUCKET` | `intake-xml` | Target bucket for intake XML |

## Failure Modes

| When | Behaviour |
|------|-----------|
| MinIO upload fails (network, auth, bucket missing) | `MinioXmlStorageAdapter.store` throws; circuit breaker may open. No DB row created. REST returns 5xx. Camel Kafka route does not ack → DLC retries 3× with exponential backoff → `document.intake.dlq`. Same envelope as today. |
| MinIO succeeds, DB tx fails | Tx rolls back, MinIO object orphaned. REST 5xx; Kafka not acked → retry. Retry generates a new `documentId`, new MinIO key, new row — same non-idempotency the inline-XML path has today. Orphan accepted. |
| XSD/Schematron validation fails | Row saved with status=INVALID, no `StartSagaCommand` published. MinIO object retained for audit. Same as today. |
| Validation throws unexpectedly | `markFailed(...)` → status=FAILED, no `StartSagaCommand`. MinIO object retained. Same as today. |
| MinIO outage during steady state | Circuit breaker fails fast; intake returns 5xx; backlog accumulates in upstream Kafka producers / REST clients. Operationally identical to MinIO outage in the PDF service today. |

## Testing

### Unit

| Test class | Covers |
|------------|--------|
| `MinioXmlStorageAdapterTest` | Mocked `S3Client` — verifies bucket, key (`YYYY/MM/DD/<documentId>.xml`), `Content-Type: application/xml`, content length, returned `s3://...` URI; circuit breaker + error wrapping. |
| `IncomingDocumentTest` | Updated for `xmlContentUrl` invariant; existing state-machine tests unchanged. |
| `StartSagaCommandTest` | JSON round-trip of `xmlContentUrl`; builder behaviour. |
| `DocumentIntakeApplicationServiceTest` | Mocked `XmlStoragePort` — asserts (a) `store(...)` called before any repository write, (b) returned URI flows into the aggregate and into the published `StartSagaCommand`, (c) MinIO failure → no repository call + exception propagates, (d) repository failure after MinIO success → exception propagates (no compensation expected). |
| `EventPublisherTest` | JSON payload contains `xmlContentUrl`, not `xmlContent`. |

### Integration (Spring + H2)

| Test class | Change |
|------------|--------|
| `DocumentIntakeServiceIntegrationTest` (existing) | Replace inline `xmlContent` assertions with `xmlContentUrl` assertions; mock `XmlStoragePort`. |
| `MinioXmlStorageAdapterIT` (new, integration profile) | Testcontainers MinIO container — real round-trip: upload, list, fetch, assert content matches. Skipped without Docker/Podman. |

### CDC Integration (`*CdcIT`, external containers)

| Test class | Change |
|------------|--------|
| `DocumentIntakeCdcIT.OutboxPatternTests` | Outbox `payload` contains `xmlContentUrl` (S3 URI), does **not** contain `xmlContent`. |
| `DocumentIntakeCdcIT.CdcFlowTests` | Kafka `saga.commands.orchestrator` message has `xmlContentUrl` instead of `xmlContent`. |
| `DocumentIntakeCdcIT.DocumentTypeTests` | Each document type still routes correctly; payload size is now small (URI not full XML). |
| `scripts/test-containers-start.sh` | Pre-create the `intake-xml` bucket alongside the existing `taxinvoices` bucket. |

### Coverage

JaCoCo per-package 90% line coverage gate (existing). New adapter package + the modified application/use-case package both must clear that bar.

## Deployment Sequencing

Because this spec deliberately breaks the orchestrator until sub-project B ships, deployment order matters.

1. **Sub-project A (this spec)** — merge to `main`. Intake produces `StartSagaCommand{xmlContentUrl}`. **Orchestrator-service starts failing to deserialize**. Sagas already in flight before the deploy keep using their stored `xml_content`; new sagas don't progress past intake.
2. **Sub-project B** (separate brainstorm/spec/PR) — orchestrator reads `xmlContentUrl`, fetches from MinIO, forwards `xmlContentUrl` (or fetches+forwards XML — a sub-project-B design decision) to downstream saga commands. Downstream services updated similarly.

**Environments:** Dev/test only after step 1. Do not promote intake past dev until step 2 ships.

**`saga-integration-tests` package** (full pipeline IT) will fail after step 1 until step 2 lands. Acceptable; it is the signal that step 2 is needed.

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Orphan MinIO objects (DB rollback after MinIO write) | Low — requires DB failure within ms of MinIO success | Accepted; reconciliation job deferred. Add `MinioCleanupService` later if monitoring shows accumulation. |
| MinIO outage blocks all intake | Medium — single dependency | `@CircuitBreaker(name = "minio")` fails fast; REST returns 5xx; Kafka retries via Camel DLC → DLQ. Operationally identical to MinIO outage in the PDF service today. |
| Orchestrator deploy lags intake deploy | High — this is the explicit known-broken window | Sub-project B's runbook should require intake + orchestrator to deploy together. |
| `documentId.xml` key collision on retry | Very low — `documentId` is fresh per `submitInvoice` call | Acceptable; same non-idempotency the inline-XML path has today. |
| Test container MinIO bucket not pre-created | Medium — test infra change | Update `test-containers-start.sh` to create `intake-xml` alongside `taxinvoices`. |

## Rollback

Pure code revert. Because V1 is edited in place and is the dev-only consolidated baseline, re-applying the prior V1 is a destructive reset of the dev DB — acceptable per the existing `services/CLAUDE.md` posture on V1.
