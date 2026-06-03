# Layer Leakage Fixes Design

**Date:** 2026-06-03
**Service:** document-intake-service
**Status:** Approved

## Problem

A code review identified 7 layer violations where framework or infrastructure concerns
crossed into the domain or application layers. Left unfixed, these create compile-time
coupling to Spring, Camel, and Java serialization from layers that should be framework-free.

## Goals

1. Remove all Spring/Camel imports from the domain and application layers.
2. Keep the domain model free of serialization contracts.
3. Confine XML parsing infrastructure to the infrastructure adapter.
4. Replace Camel-mediated REST dispatch with a direct use-case call.
5. Replace the bypassed `OutboxEventRepository` abstraction with a proper port.

## Issues and Fixes

### Issue 1 — `DataIntegrityViolationException` caught in the application service

**Location:** `application/usecase/DocumentIntakeApplicationService.java`

Spring's `DataIntegrityViolationException` is caught in `submitDocument()` to detect
duplicate document numbers. This is a Spring Data type with no business meaning.

**Fix:** Create `domain/exception/DuplicateDocumentException`. Catch
`DataIntegrityViolationException` in `JpaDocumentRepository.save()` and re-throw
`DuplicateDocumentException`. The application service catches the domain exception instead.

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

The `DataIntegrityViolationException` import remains in `JpaDocumentRepository` where
it belongs. The application service imports only `DuplicateDocumentException`.

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

**Fix:** Create `application/port/out/OutboxHealthPort`. Implement it in
`JpaOutboxEventRepository` via a one-line delegation. The health indicator injects
the port.

```java
// application/port/out/OutboxHealthPort.java
public interface OutboxHealthPort {
    long countByStatus(OutboxStatus status);
}
```

```java
// JpaOutboxEventRepository — add to class declaration and body
public class JpaOutboxEventRepository implements OutboxEventRepository, OutboxHealthPort {

    @Override
    public long countByStatus(OutboxStatus status) {
        return springRepository.countByStatus(status);
    }
}
```

```java
// OutboxHealthIndicator — replace SpringDataOutboxRepository with port
private final OutboxHealthPort outboxHealthPort;

// usage unchanged; only the field type and import change
long failedCount = outboxHealthPort.countByStatus(OutboxStatus.FAILED);
long pendingCount = outboxHealthPort.countByStatus(OutboxStatus.PENDING);
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

**New dependency** (managed by existing Spring Cloud BOM):
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

**`application.yml` addition:**
```yaml
resilience4j:
  ratelimiter:
    instances:
      documentIntake:
        limit-for-period: ${app.rate-limit.requests-per-second:10}
        limit-refresh-period: 1s
        timeout-duration: 0s
```

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

**Behavioral note:** The Camel throttler applied rate limits per `correlationId`
(per-client). The Resilience4j `@RateLimiter` is global per JVM instance. This is
an intentional trade-off: global limiting is simpler and correct for single-instance
deployments.

---

### Issue 5 — `EventStatus` duplicates `DocumentStatus`

**Location:** `application/dto/event/EventStatus.java`,
`application/usecase/DocumentIntakeApplicationService.java`

`EventStatus` defines the same six values as `domain/model/DocumentStatus`. Conversion
between them in the application service (`EventStatus.RECEIVED.getValue()`, etc.) is
redundant and creates a sync risk.

**Fix:** Delete `EventStatus.java`. Replace all four `EventStatus.X.getValue()` call
sites in `DocumentIntakeApplicationService` with `document.getStatus().name()`. The
string values are identical, so this is a zero-behavior-change substitution.

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
| `application/port/out/OutboxHealthPort.java` | **New** |
| `application/usecase/DocumentIntakeApplicationService.java` | Modify — remove Spring import, XML infrastructure, `EventStatus` usage |
| `application/dto/event/EventStatus.java` | **Delete** |
| `application/dto/event/StartSagaCommand.java` | Modify — remove 3 dead imports |
| `infrastructure/adapter/out/validation/TedaXmlValidationAdapter.java` | Modify — add `normalize()` implementation |
| `infrastructure/adapter/out/persistence/JpaDocumentRepository.java` | Modify — catch and translate `DataIntegrityViolationException` |
| `infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java` | Modify — implement `OutboxHealthPort` |
| `infrastructure/adapter/out/health/OutboxHealthIndicator.java` | Modify — inject `OutboxHealthPort` |
| `infrastructure/adapter/in/web/DocumentIntakeController.java` | Modify — remove Camel, add Resilience4j rate limiter |
| `infrastructure/config/camel/CamelConfig.java` | Modify — remove `direct:` route and `RateLimitProperties` injection |
| `pom.xml` | Modify — add `resilience4j-spring-boot3` |
| `src/main/resources/application.yml` | Modify — add `resilience4j.ratelimiter` config block |

## Test Impact

- Tests asserting `IllegalStateException` for duplicate documents must be updated to
  assert `DuplicateDocumentException` (or verify the HTTP 409 response if testing via
  the controller).
- Tests for `DocumentIntakeApplicationService` that mock `XmlValidationPort` must stub
  the new `normalize()` method (return the input unchanged for most tests).
- `CamelConfigTest` — remove assertions for `direct:document-intake` route.
- Controller tests that expect Camel-mediated invocation must be rewritten to call
  the use case directly via MockMvc.
- No changes needed to validation, outbox, or domain state-machine tests.

## Alternatives Considered

| Decision | Rejected alternative | Reason |
|----------|---------------------|--------|
| `normalize()` on port | Normalize internally in adapter (not visible to app service) | App service needs the normalized string for persistence and event publishing, not just validation |
| `OutboxHealthPort` | Add `countByStatus` to saga-commons `OutboxEventRepository` | saga-commons is a shared library; health monitoring is a service-local concern |
| Resilience4j global rate limit | Per-client rate limit (Bucket4j filter) | Simpler; adequate for single-instance deployment; avoids a second new dependency |
| Keep `EventStatus` | Map from `DocumentStatus` explicitly | Identical values; mapping adds no value and creates sync risk |
