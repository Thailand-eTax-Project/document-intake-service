# Intake XML Content MinIO Offload Design

**Date:** 2026-05-02
**Service:** document-intake-service (+ minimal guard in orchestrator-service)
**Status:** Approved

## Problem

`document-intake-service` stores raw XML payloads in two places:

1. PostgreSQL column `incoming_documents.xml_content TEXT NOT NULL`
2. The `xmlContent` field of `StartSagaCommand`, which Debezium ships through `saga.commands.orchestrator` to `orchestrator-service` — and from there into ~7 downstream `saga.command.*` topics.

For Thai e-Tax invoice XML this can be tens to hundreds of KB per document. The result is large DB rows, large outbox payloads, large Kafka messages, and full duplication of the same blob across PostgreSQL plus every saga step that forwards it.

## Goal

Move the XML payload into MinIO (S3-compatible object store) once, at intake. Replace every inline copy with a single S3 URI reference (`xmlContentUrl` in the aggregate, the DB column, and the saga command — single name across all layers, no translation in mappers). Validation, the document state machine, the outbox pattern, and the trace event flow are unchanged.

## Scope

**In scope (this spec — sub-project A):**

- `document-intake-service` uploads XML to MinIO before persisting the `IncomingDocument`.
- `incoming_documents.xml_content` is replaced by `xml_content_url`.
- `StartSagaCommand.xmlContent` is replaced by `xmlContentUrl`.
- New outbound port `XmlStoragePort` + new adapter `MinioXmlStorageAdapter`.
- New configurable bucket via `MINIO_INTAKE_BUCKET` (default `intake-xml`).
- Existing Flyway `V1__baseline_schema.sql` is edited in place (dev-only baseline, per `services/CLAUDE.md`).
- **One small guard in `orchestrator-service`'s `StartSagaCommandConsumer`**: reject any `StartSagaCommand` that has a null/blank `xmlContent` (the orchestrator's `StartSagaCommand` DTO uses `@JsonIgnoreProperties(ignoreUnknown = true)`, so without this guard a missing `xmlContent` would silently become `null` and propagate downstream as garbage). This is the *only* orchestrator change in sub-project A. See **Deployment Sequencing**.

**Out of scope (deliberate):**

- Reading or fetching `xmlContentUrl` in `orchestrator-service`. After this spec ships, the orchestrator's guard rejects every new `StartSagaCommand` from intake — by design — and intake's saga path is dev/test broken until sub-project B lands. **Do not promote intake past dev until sub-project B ships.**
- `orchestrator-service`'s downstream saga consumers (tax-invoice processing, signing, PDF generation, etc.) — they continue to read `xmlContent` from the orchestrator's `SagaCommandPublisher`. Sub-project B will rewire them.
- Periodic MinIO orphan cleanup job. The existing pattern in `taxinvoice-pdf-generation-service.MinioCleanupService` can be mirrored later if monitoring shows accumulation.
- Data migration of existing rows. V1 is the dev consolidated baseline, so the schema change is destructive and acceptable for dev/test.
- XML content checksum / integrity verification. We don't store a checksum because no current consumer re-validates after fetch; sub-project B can add `xmlChecksum` to `StartSagaCommand` if integrity-on-fetch becomes a requirement.

## Decision

### Bucket

Configurable via env var, default `intake-xml`. Separate from the existing `taxinvoices` bucket used by `taxinvoice-pdf-generation-service` because the lifecycle, producer, and consumers are different.

### Object key layout

`YYYY/MM/DD/<documentId>.xml`

- Date-partitioned (matches the PDF service convention).
- Uses `documentId` rather than a fresh UUID, so the key is reproducible from the row alone — useful for debugging.

### URL format in messages

`s3://<bucket>/<key>`, e.g. `s3://intake-xml/2026/05/02/0c3b6e10-...xml`.

- Endpoint-agnostic — survives MinIO endpoint rotation.
- Encodes both bucket and key, so consumers don't have to assume a bucket name.
- Standard S3 URI scheme; readable by tooling.
- **Trade-off, called out explicitly:** because the bucket name is embedded in the persisted URI (DB column + saga payload), renaming `MINIO_INTAKE_BUCKET` later requires a data migration of existing rows. The simpler "store the bare key + resolve at read" pattern used by `taxinvoice-pdf-generation-service.MinioStorageAdapter` (whose `store()` returns the bare key and exposes `resolveUrl(key)` separately) avoids this; we deliberately diverge from that pattern here to keep the saga payload self-contained.

### DB schema

Replace `xml_content TEXT NOT NULL` with `xml_content_url VARCHAR(512) NOT NULL` (single source of truth: the MinIO object). Edit `V1__baseline_schema.sql` in place; do not introduce V2.

### Upload timing

Upload to MinIO **before** the `@Transactional` block opens, but **after** the existing `existsByDocumentNumber` dedupe check. Order:

1. Parse the document number from the XML (existing logic).
2. `existsByDocumentNumber(documentNumber)` — existing duplicate-submission rejection. Throws `IllegalStateException` on duplicate; no MinIO upload happens.
3. `xmlContentUrl = xmlStoragePort.store(documentId, xmlContent)` — network I/O, no DB tx.
4. Open `@Transactional`. Inside the tx: save `IncomingDocument(xmlContentUrl=...)`, write all outbox events (RECEIVED trace → VALIDATED trace → [if valid] StartSagaCommand + FORWARDED trace), commit.

Validation still receives the in-memory `xmlContent` from the request — we do **not** re-fetch from MinIO for validation.

This preserves the existing single-transaction outbox guarantee: every outbox event for a document is committed atomically with the document row's terminal state. Doing the dedupe check *before* the upload prevents Kafka redeliveries of already-processed messages from generating a fresh MinIO orphan on every retry. The remaining orphan window (DB tx fails after MinIO upload) is rare and accepted (see Risks).

## Components

### New (intake-service)

| Layer | Component | Purpose |
|-------|-----------|---------|
| `application/port/out` | `XmlStoragePort` (interface) | `String store(String documentId, String xmlContent)` returning an `s3://<bucket>/<key>` URI. `void delete(String s3Uri)` for future use. **Note:** returns the full S3 URI directly, unlike `taxinvoice-pdf-generation-service.MinioStorageAdapter.store()` which returns the bare key. |
| `infrastructure/adapter/out/storage` | `MinioXmlStorageAdapter` | `S3Client`-backed implementation. `@CircuitBreaker(name = "minio")` on `store`/`delete`. Micrometer `Timer`s for `intake.minio.store` and `intake.minio.delete`. Structurally similar to the PDF adapter, but the return contract differs (URI, not key). |
| `infrastructure/config` | `MinioConfig` | `S3Client` bean from `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_REGION` (path-style access enabled). Mirrors the PDF service config. |

### Modified (intake-service)

| Component | Change |
|-----------|--------|
| `domain/model/IncomingDocument` | Field `xmlContent: String` → `xmlContentUrl: String`. Non-null/non-blank invariant moves to the new field. State machine unchanged. |
| `infrastructure/adapter/out/persistence/IncomingDocumentEntity` | `@Column(name="xml_content")` → `@Column(name="xml_content_url", length=512, nullable=false)`. |
| `infrastructure/adapter/out/persistence/JpaDocumentRepository` | Mapper updated for new field name. |
| `application/dto/event/StartSagaCommand` | Field `xmlContent` → `xmlContentUrl`. Builder, getter, and `@JsonCreator` updated. |
| `infrastructure/adapter/out/messaging/EventPublisher.publishStartSagaCommand` | Pass `xmlContentUrl` instead of `xmlContent`. No other change. |
| `application/usecase/DocumentIntakeApplicationService.submitDocument` | After the existing `existsByDocumentNumber` check and before opening the `@Transactional` block, call `xmlStoragePort.store(...)`; pass `xmlContentUrl` into the aggregate; pass the in-memory `xmlContent` to the validator. |
| `src/main/resources/db/migration/V1__baseline_schema.sql` | Replace `xml_content TEXT NOT NULL` with `xml_content_url VARCHAR(512) NOT NULL`. |
| `src/main/resources/application.yml` | Add MinIO client config block + `resilience4j.circuitbreaker.instances.minio.*`. Configuration values mirror those in `taxinvoice-pdf-generation-service/src/main/resources/application.yml` (`resilience4j.circuitbreaker.instances.minio.*`). |

### Modified (orchestrator-service — minimal guard only)

| Component | Change |
|-----------|--------|
| `infrastructure/adapter/in/messaging/StartSagaCommandConsumer.handleStartSagaCommand` | Add a guard at the top of the method (before the existing `DocumentMetadata.builder().xmlContent(command.getXmlContent())` call at `StartSagaCommandConsumer.java:75-76`): if `command.getXmlContent()` is null or blank, log a clear error (`"StartSagaCommand for documentId={} has no xmlContent — sub-project B not yet deployed; rejecting"`) and treat as a permanent (non-retryable) error. This converts the otherwise-silent null-propagation into a loud, contained rejection while sub-project B is in flight. **No other orchestrator changes.** |

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
| Duplicate submission (Kafka redelivery of an already-processed message, or external retry) | `existsByDocumentNumber` rejects with `IllegalStateException` *before* the MinIO upload. No orphan, no extra MinIO call. |
| MinIO upload fails (network, auth, bucket missing) | `MinioXmlStorageAdapter.store` throws; circuit breaker may open. No DB row created. REST returns 5xx. Camel Kafka route does not ack → DLC retries 3× with exponential backoff → `document.intake.dlq`. Same envelope as today. |
| MinIO succeeds, DB tx fails | Tx rolls back, MinIO object orphaned. REST 5xx; Kafka not acked → retry. On retry the upload happens again with the same `documentId` → same key → idempotent overwrite (no second orphan). Single orphan from the original failure remains; accepted. |
| XSD/Schematron validation fails | Row saved with status=INVALID, no `StartSagaCommand` published. MinIO object retained for audit. Same as today. |
| Validation throws unexpectedly | `markFailed(...)` → status=FAILED, no `StartSagaCommand`. MinIO object retained. Same as today. |
| MinIO outage during steady state | Circuit breaker fails fast; intake returns 5xx; backlog accumulates in upstream Kafka producers / REST clients. Operationally identical to MinIO outage in the PDF service today. |
| Orchestrator receives a `StartSagaCommand` with null `xmlContent` (intake deployed, sub-project B not yet) | Guard in `StartSagaCommandConsumer` logs a permanent error and drops the command. Loud, contained, no downstream null propagation. |

## Testing

### Unit (intake-service)

| Test class | Covers |
|------------|--------|
| `MinioXmlStorageAdapterTest` (new) | Mocked `S3Client` — verifies bucket, key (`YYYY/MM/DD/<documentId>.xml`), `Content-Type: application/xml`, content length, returned `s3://...` URI; circuit breaker + error wrapping. |
| `IncomingDocumentTest` (existing) | Updated for `xmlContentUrl` invariant; existing state-machine tests unchanged. |
| `StartSagaCommandTest` (existing) | JSON round-trip of `xmlContentUrl`; builder behaviour. |
| `DocumentIntakeServiceTest` (existing — at `application/usecase/DocumentIntakeServiceTest.java`) | Mocked `XmlStoragePort` — asserts (a) `store(...)` is called *after* `existsByDocumentNumber` and *before* any repository write, (b) returned URI flows into the aggregate and into the published `StartSagaCommand`, (c) MinIO failure → no repository call + exception propagates, (d) duplicate-document path → no MinIO call, (e) repository failure after MinIO success → exception propagates (no compensation expected). |
| `EventPublisherTest` (existing) | JSON payload contains `xmlContentUrl`, not `xmlContent`. |

### Integration — Spring + H2 (intake-service)

| Test class | Change |
|------------|--------|
| `MinioXmlStorageAdapterIT` (new, integration profile) | Testcontainers MinIO container — real round-trip: upload, list, fetch, assert content matches. Skipped without Docker/Podman. |

(Note: the originally-mentioned `DocumentIntakeServiceIntegrationTest` does not exist today. If end-to-end Spring-context coverage of the new flow is needed beyond what `DocumentIntakeServiceTest` + `DocumentIntakeCdcIT` provide, create one as part of this work; otherwise the unit + CDC layers are sufficient.)

### CDC Integration (`*CdcIT`, external containers)

| Test class | Change |
|------------|--------|
| `DocumentIntakeCdcIT.OutboxPatternTests` | Outbox `payload` contains `xmlContentUrl` (S3 URI), does **not** contain `xmlContent`. |
| `DocumentIntakeCdcIT.CdcFlowTests` | Kafka `saga.commands.orchestrator` message has `xmlContentUrl` instead of `xmlContent`. |
| `DocumentIntakeCdcIT.DocumentTypeTests` | Each document type still routes correctly; payload size is now small (URI not full XML). |
| `docker/docker-compose.test.yml` (`minio-init` service, lines ~184–198) | Add `mc mb --ignore-existing local/intake-xml` alongside the existing `taxinvoices`, `invoices`, `signed-xml-documents`, `etax-signed-pdfs`, `invoice-documents` lines. (Bucket creation lives in this docker-compose file, *not* in `scripts/test-containers-start.sh`.) |

### Coverage

JaCoCo per-package 90% line coverage gate (existing). New adapter package + the modified application/use-case package both must clear that bar.

## Deployment Sequencing

This spec deliberately leaves intake's saga path dev/test broken until sub-project B ships. The orchestrator guard ensures the breakage is loud and contained.

1. **Sub-project A (this spec)** — merge to `main`. Intake produces `StartSagaCommand{xmlContentUrl}`, no `xmlContent`. Orchestrator's `StartSagaCommandConsumer` guard rejects every such command with a clear log line and treats it as a permanent error. Sagas already in flight before the deploy keep running with their stored `xml_content` in `saga_instances`; new sagas are rejected at the orchestrator's consumer.
2. **Sub-project B** (separate brainstorm/spec/PR) — orchestrator reads `xmlContentUrl`, fetches from MinIO, removes the rejection guard, forwards `xmlContentUrl` (or fetches+forwards XML — a sub-project-B design decision) to downstream saga commands. Downstream services updated similarly.

**Environments:** Dev/test only after step 1. Do not promote intake past dev until step 2 ships.

**`saga-integration-tests` package** (full pipeline IT) will fail after step 1 until step 2 lands. Acceptable; it is the signal that step 2 is needed.

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Orphan MinIO objects (DB rollback after MinIO write) | Low — requires DB failure within ms of MinIO success | Accepted; reconciliation job deferred. Same `documentId` → same key on retry, so retries don't multiply orphans. Add `MinioCleanupService` later if monitoring shows accumulation. |
| MinIO outage blocks all intake | Medium — single dependency | `@CircuitBreaker(name = "minio")` fails fast; REST returns 5xx; Kafka retries via Camel DLC → DLQ. Operationally identical to MinIO outage in the PDF service today. |
| Orchestrator deploy lags intake deploy | High — this is the explicit known-broken window, narrowed by the guard | Guard makes the failure loud and contained at the orchestrator's consumer. Sub-project B's runbook should require intake + orchestrator to deploy together. |
| Bucket rename later requires data migration | Low — `MINIO_INTAKE_BUCKET` rarely changes | Accepted trade-off of the embedded-bucket URI format (see "URL format in messages"). |
| Test container MinIO bucket not pre-created | Medium — test infra change | Add `local/intake-xml` to the `minio-init` service in `docker/docker-compose.test.yml`. |

## Rollback

Pure code revert (both intake-service and the orchestrator guard). Because V1 is edited in place and is the dev-only consolidated baseline, re-applying the prior V1 is a destructive reset of the dev DB — acceptable per the existing `services/CLAUDE.md` posture on V1.
