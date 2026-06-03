# Layer Leakage Fixes Design

**Date:** 2026-06-03
**Service:** document-intake-service
**Status:** Approved

## Problem

A code review identified 7 layer violations where framework or infrastructure concerns
crossed into the domain or application layers. Left unfixed, these create compile-time
coupling to Spring, Camel, and Java serialization from layers that should be framework-free.

## Goals

1. Remove Spring infrastructure exception types and XML parser imports from the application
   layer. Framework lifecycle annotations (`@Service`, `@Transactional`) on application
   service classes are acceptable and outside this fix set.
2. Keep the domain model free of serialization contracts.
3. Confine XML parsing infrastructure to the infrastructure adapter.
4. Replace Camel-mediated REST dispatch with a direct use-case call.
5. Replace the bypassed `OutboxEventRepository` abstraction with a proper port.

## Issues and Fixes

### Issue 1 — `DataIntegrityViolationException` caught in the application service

**Location:** `application/usecase/DocumentIntakeApplicationService.java`

Spring's `DataIntegrityViolationException` is caught in `submitDocument()` to detect
duplicate document numbers. This is a Spring Data type with no business meaning.

`submitDocument()` has **two** code paths that produce a duplicate-document response:

1. **Pre-check path** — `documentRepository.existsByDocumentNumber()` returns `true`
   before save. Currently throws `IllegalStateException`.
2. **Concurrent path** — a race-condition duplicate slips past the pre-check and surfaces
   as `DataIntegrityViolationException` from `jpaRepository.save()`. Currently caught and
   re-thrown as `IllegalStateException` from within the application service.

Both paths must be fixed together. Issue 4 replaces `catch (IllegalStateException e)` → 409
in the controller with `catch (DuplicateDocumentException e)` → 409. If only the concurrent
path is fixed, the pre-check path silently falls through to the 500 handler.

**Fix:** Create `domain/exception/DuplicateDocumentException`. In `JpaDocumentRepository.save()`,
catch `DataIntegrityViolationException` and re-throw `DuplicateDocumentException` — the Spring
exception never leaves the infrastructure adapter. In the application service, replace the
pre-check `IllegalStateException` with `DuplicateDocumentException`, and remove the
`try/catch` around `documentRepository.save()` (the domain exception now propagates naturally).

```java
// domain/exception/DuplicateDocumentException.java
public class DuplicateDocumentException extends RuntimeException {
    private final String documentNumber;

    public DuplicateDocumentException(String documentNumber, Throwable cause) {
        super("Document number already exists: " + documentNumber, cause);
        this.documentNumber = documentNumber;
    }

    public String getDocumentNumber() { return documentNumber; }
}
```

```java
// JpaDocumentRepository.save() — infrastructure only
try {
    return toDomain(jpaRepository.save(toEntity(document)));
} catch (DataIntegrityViolationException e) {
    throw new DuplicateDocumentException(document.getDocumentNumber(), e);
}
```

```java
// DocumentIntakeApplicationService.submitDocument() — application service
// Pre-check path: throw domain exception, not IllegalStateException
if (documentRepository.existsByDocumentNumber(documentNumber)) {
    log.warn("Document number {} already exists", documentNumber);
    metrics.incrementFailed("duplicate_document_number");
    throw new DuplicateDocumentException(documentNumber, null);
}

// Concurrent path: remove the try/catch — DuplicateDocumentException propagates from save()
document = documentRepository.save(document);
```

The `DataIntegrityViolationException` import is removed from the application service entirely.
It remains in `JpaDocumentRepository` where it belongs. The application service imports only
`DuplicateDocumentException`.

---

### Issue 2 — XML infrastructure (DOM/XSLT) in the application service

**Location:** `application/usecase/DocumentIntakeApplicationService.java`

~90 lines of XML infrastructure: `DocumentBuilderFactory`, `TransformerFactory`, a static
initializer with XXE hardening, `normalizeXml()`, and `stripWhitespaceOnlyTextNodes()`.
The application service should orchestrate ports, not implement parsers.

**Fix:** Add `normalize(String) → String` to `XmlValidationPort`. Move all XML
infrastructure into `TedaXmlValidationAdapter` as the `@Override` implementation.

```java
// application/port/out/XmlValidationPort.java
public interface XmlValidationPort {
    String normalize(String xmlContent);          // NEW
    ValidationResult validate(String xmlContent);
    String extractDocumentNumber(String xmlContent);
    DocumentType extractDocumentType(String xmlContent);
}
```

In `submitDocument()`, replace the removed static call with:
```java
xmlContent = validationService.normalize(xmlContent);
```

The `validationService` field already exists. No new injection needed.

The two static fields (`XML_DBF`, `XML_TF`), the static initializer, and both helper
methods move verbatim into `TedaXmlValidationAdapter`, which already initializes
heavyweight resources once at startup.

---

### Issue 3 — `OutboxHealthIndicator` bypasses `OutboxEventRepository` abstraction

**Location:** `infrastructure/adapter/out/health/OutboxHealthIndicator.java`

The health indicator injects `SpringDataOutboxRepository` directly — a Spring Data
interface — because `OutboxEventRepository` (saga-commons) does not expose count
operations. This couples the health adapter to the persistence layer type.

**Fix:** Create `application/port/out/OutboxHealthPort` using a local enum so the port
carries no saga-commons types. Define `OutboxHealthStatus` in the same package.
Implement the port in `JpaOutboxEventRepository`, translating from `OutboxStatus`
(saga-commons) to `OutboxHealthStatus` in the adapter. The health indicator injects
the port.

```java
// application/port/out/OutboxHealthStatus.java
public enum OutboxHealthStatus { PENDING, PUBLISHED, FAILED }

// application/port/out/OutboxHealthPort.java
public interface OutboxHealthPort {
    long countByStatus(OutboxHealthStatus status);
}
```

```java
// JpaOutboxEventRepository — implement OutboxHealthPort with translation
public class JpaOutboxEventRepository implements OutboxEventRepository, OutboxHealthPort {

    @Override
    public long countByStatus(OutboxHealthStatus status) {
        OutboxStatus sagaStatus = switch (status) {
            case PENDING   -> OutboxStatus.PENDING;
            case PUBLISHED -> OutboxStatus.PUBLISHED;
            case FAILED    -> OutboxStatus.FAILED;
        };
        return springRepository.countByStatus(sagaStatus);
    }
}
```

```java
// OutboxHealthIndicator — inject OutboxHealthPort; no saga-commons import
private final OutboxHealthPort outboxHealthPort;

long failedCount  = outboxHealthPort.countByStatus(OutboxHealthStatus.FAILED);
long pendingCount = outboxHealthPort.countByStatus(OutboxHealthStatus.PENDING);
```

---

### Issue 4 — Camel leaking into the REST adapter

**Location:** `infrastructure/adapter/in/web/DocumentIntakeController.java`,
`infrastructure/config/camel/CamelConfig.java`

The controller sends to `direct:document-intake` via `ProducerTemplate` purely to
apply a Camel throttler. Camel wraps all exceptions in `CamelExecutionException`,
forcing the controller to unwrap them manually — coupling the HTTP adapter to Camel's
internal exception model.

**Fix (Option C):** Keep the Kafka Camel route unchanged (DLQ and retry are legitimate
uses). Remove the `direct:document-intake` route. The controller calls
`submitDocumentUseCase.submitDocument()` directly. Rate limiting is provided by
Resilience4j's `@RateLimiter` AOP annotation.

**New dependency** — Spring Cloud BOM does not manage Resilience4j. Match the version
used by sibling services (`xml-signing-service`, `cancellationnote-pdf-generation-service`):

```xml
<!-- pom.xml properties section -->
<resilience4j.version>2.2.0</resilience4j.version>

<!-- pom.xml dependencies -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>${resilience4j.version}</version>
</dependency>
```

**`application.yml` addition** — both `requests-per-second` and `time-period-seconds`
are referenced so `RateLimitProperties` values remain effective:
```yaml
resilience4j:
  ratelimiter:
    instances:
      documentIntake:
        limit-for-period: ${app.rate-limit.requests-per-second:10}
        limit-refresh-period: "${app.rate-limit.time-period-seconds:60}s"
        timeout-duration: 0s
```

Note: `limit-refresh-period` is a Spring Duration — the property must be a valid
duration string, hence the `"…s"` interpolation. The default behaviour is
10 requests per 60-second window, identical to the previous Camel throttler default.

**Controller changes:**
- Remove `ProducerTemplate camelProducer` field and injection.
- Remove `CamelExecutionException` import and unwrapping block.
- Call `submitDocumentUseCase.submitDocument(xmlContent, "REST", effectiveCorrelationId)`
  directly in the try block.
- Add `@RateLimiter(name = "documentIntake", fallbackMethod = "rateLimitFallback")` on
  the POST endpoint method.
- Add `rateLimitFallback(String xmlContent, String correlationId, RequestNotPermitted ex)`
  method returning HTTP 429 with a JSON error body.
- Replace the `catch (IllegalStateException e)` → 409 block with
  `catch (DuplicateDocumentException e)` → 409. `IllegalStateException` no longer
  has a dedicated handler; unexpected state exceptions fall through to the generic
  500 handler.
- `ValidationProperties` remains injected — it is used for the XML size guard and
  has no connection to the Camel route being removed.

**`CamelConfig` changes:**
- Remove the `from("direct:document-intake")` route entirely.
- Remove `RateLimitProperties` injection from `CamelConfig` constructor (it was only
  used for the throttler on the removed route).
- `RateLimitProperties` itself is kept — its values feed the `application.yml`
  Resilience4j property references.

**Behavioral note:** The Camel throttler applied limits per `correlationId` header
value. Because the controller generates a server-side UUID when no `X-Correlation-ID`
is sent (`effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID()`),
every unauthenticated request received its own bucket — making the throttler effectively
a no-op for most traffic. The Resilience4j `@RateLimiter` is global per JVM instance,
which is a stricter and more meaningful constraint.

---

### Issue 5 — `EventStatus` duplicates `DocumentStatus`

**Location:** `application/dto/event/EventStatus.java`,
`application/usecase/DocumentIntakeApplicationService.java`

`EventStatus` defines the same six values as `domain/model/DocumentStatus`. Conversion
between them in the application service (`EventStatus.RECEIVED.getValue()`, etc.) is
redundant and creates a sync risk.

**Fix:** Delete `EventStatus.java` and its test class `EventStatusTest.java`. Replace
all four `EventStatus.X.getValue()` call sites in `DocumentIntakeApplicationService`
with `document.getStatus().name()`. The string values are identical, so this is a
zero-behavior-change substitution.

`EventStatus.fromValue(String)` is a public method but has no callers outside this
service (confirmed by grep across all sibling services). No external consumer depends
on it.

`DocumentIntakeServiceTest.java` contains assertions against `EventStatus` string
values (e.g., `assertThat(events.get(0).getStatus()).isEqualTo(EventStatus.RECEIVED.getValue())`)
— these must be updated to use the raw string literals `"RECEIVED"`, `"VALIDATED"`,
`"FORWARDED"`, `"INVALID"` instead.

---

### Issue 6 — Dead Jakarta Validation imports in `StartSagaCommand`

**Location:** `application/dto/event/StartSagaCommand.java`

Three unused imports:
```java
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
```

**Fix:** Remove all three. No other changes to the file.

---

### Issue 7 — `ValidationResult` implements `Serializable`

**Location:** `domain/model/ValidationResult.java`

The domain record implements `Serializable` — a Java serialization transport contract
that has no business meaning. Actual serialization is JSON, handled by Jackson in
`JpaDocumentRepository`.

**Fix:** Remove `implements Serializable` from the record declaration.

---

## File Inventory

| File | Action |
|------|--------|
| `domain/exception/DuplicateDocumentException.java` | **New** |
| `domain/model/ValidationResult.java` | Modify — remove `implements Serializable` |
| `application/port/out/XmlValidationPort.java` | Modify — add `normalize()` method |
| `application/port/out/OutboxHealthStatus.java` | **New** — local enum (PENDING, PUBLISHED, FAILED) |
| `application/port/out/OutboxHealthPort.java` | **New** — uses `OutboxHealthStatus`, no saga-commons import |
| `application/usecase/DocumentIntakeApplicationService.java` | Modify — remove Spring import, XML infrastructure, `EventStatus` usage |
| `application/dto/event/EventStatus.java` | **Delete** |
| `application/dto/event/StartSagaCommand.java` | Modify — remove 3 dead imports |
| `infrastructure/adapter/out/validation/TedaXmlValidationAdapter.java` | Modify — add `normalize()` implementation |
| `infrastructure/adapter/out/persistence/JpaDocumentRepository.java` | Modify — catch and translate `DataIntegrityViolationException` |
| `infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java` | Modify — implement `OutboxHealthPort` with `OutboxStatus` → `OutboxHealthStatus` translation |
| `infrastructure/adapter/out/health/OutboxHealthIndicator.java` | Modify — inject `OutboxHealthPort`, remove `SpringDataOutboxRepository` import |
| `infrastructure/adapter/in/web/DocumentIntakeController.java` | Modify — remove Camel, add Resilience4j rate limiter and 429 fallback |
| `infrastructure/config/camel/CamelConfig.java` | Modify — remove `direct:` route and `RateLimitProperties` injection |
| `pom.xml` | Modify — add `resilience4j-spring-boot3` with explicit `2.2.0` version property |
| `src/main/resources/application.yml` | Modify — add `resilience4j.ratelimiter` config block |
| `src/test/java/…/web/DocumentIntakeControllerTest.java` | Modify — rewrite all `ProducerTemplate` stubs to mock `submitDocumentUseCase` directly; add 429 fallback test |
| `src/test/java/…/integration/config/RestApiCdcTestConfiguration.java` | Modify — remove `ProducerTemplate` `@MockBean` and `direct:document-intake` route dependency |
| `src/test/java/…/dto/event/EventStatusTest.java` | **Delete** |
| `src/test/java/…/usecase/DocumentIntakeServiceTest.java` | Modify — replace `EventStatus.X.getValue()` assertions with raw string literals |

## Test Impact

**`DocumentIntakeControllerTest.java`** (full rewrite of submit path):
- Remove `@MockBean ProducerTemplate producerTemplate` declaration.
- Rewrite all 3 `producerTemplate.sendBodyAndHeader(...)` stub sites to mock
  `submitDocumentUseCase.submitDocument(...)` throwing the relevant exception
  (`DuplicateDocumentException` for 409, `IllegalArgumentException` for 400).
- Add a new test for the 429 rate-limit fallback: inject a `RateLimiterRegistry`,
  set `limitForPeriod` to 0 on the `documentIntake` instance, and assert that
  the endpoint returns HTTP 429.

**`RestApiCdcTestConfiguration.java`**:
- Remove the `@MockBean ProducerTemplate` declaration and any stubs that configure it.
- Remove the comment that asserts `direct:document-intake` must remain active.

**`DocumentIntakeServiceTest.java`**:
- Replace all `EventStatus.X.getValue()` assertions with raw string literals
  (`"RECEIVED"`, `"VALIDATED"`, `"FORWARDED"`, `"INVALID"`).
- Stub the new `normalize()` method on the `XmlValidationPort` mock to return the
  input unchanged (`when(validationService.normalize(any())).thenAnswer(i -> i.getArgument(0))`).

**`EventStatusTest.java`**: Delete alongside `EventStatus.java`.

**`CamelConfigTest`**: Remove assertions for `direct:document-intake` route existence.

No changes needed to validation, outbox, or domain state-machine tests.

## Alternatives Considered

| Decision | Rejected alternative | Reason |
|----------|---------------------|--------|
| `normalize()` on port | Normalize internally in adapter (not visible to app service) | App service needs the normalized string for persistence and event publishing, not just validation |
| `OutboxHealthPort` | Add `countByStatus` to saga-commons `OutboxEventRepository` | saga-commons is a shared library; health monitoring is a service-local concern |
| Resilience4j global rate limit | Per-client rate limit (Bucket4j filter) | Simpler; adequate for single-instance deployment; avoids a second new dependency |
| Keep `EventStatus` | Map from `DocumentStatus` explicitly | Identical values; mapping adds no value and creates sync risk |
| Goal 1 scoped to exceptions/parsers | Remove `@Service`/`@Transactional` from app service (JSR-330 + manual tx) | Framework lifecycle annotations are convention in Spring Boot; removing them is scope creep with no architectural benefit |
| `@EnableConfigurationProperties` on `RateLimitConfig` | Move registration to `CamelConfig` | Registration already lives on `RateLimitConfig` — removing `RateLimitProperties` from `CamelConfig` constructor has no effect on bean registration |
