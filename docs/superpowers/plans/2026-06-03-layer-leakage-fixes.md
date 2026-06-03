# Layer Leakage Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove 7 layer violations from document-intake-service — Spring infrastructure exceptions in the application layer, XML DOM/XSLT infrastructure in the application service, a bypassed port abstraction in the health indicator, Camel leaking into the REST adapter, a duplicated status enum, dead imports, and a serialization contract on a domain record.

**Architecture:** Six self-contained tasks delivered in dependency order (trivial cleanups first, largest structural change last). Each task leaves the build and tests green. Tasks 1–5 are independent of each other except that Task 4 (normalize port) requires Task 3 (DuplicateDocumentException) to be complete first so application service imports are clean.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4 (Kafka route preserved), Resilience4j 2.2.0 (added in Task 6), MockMvc, Mockito, AssertJ

---

## File Map

| File | Task | Action |
|------|------|--------|
| `domain/exception/DuplicateDocumentException.java` | 3 | Create |
| `domain/model/ValidationResult.java` | 1 | Modify |
| `application/port/out/XmlValidationPort.java` | 4 | Modify |
| `application/port/out/OutboxHealthStatus.java` | 5 | Create |
| `application/port/out/OutboxHealthPort.java` | 5 | Create |
| `application/usecase/DocumentIntakeApplicationService.java` | 2, 3, 4 | Modify |
| `application/dto/event/EventStatus.java` | 2 | Delete |
| `application/dto/event/StartSagaCommand.java` | 1 | Modify |
| `infrastructure/adapter/out/validation/TedaXmlValidationAdapter.java` | 4 | Modify |
| `infrastructure/adapter/out/persistence/JpaDocumentRepository.java` | 3 | Modify |
| `infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java` | 5 | Modify |
| `infrastructure/adapter/out/health/OutboxHealthIndicator.java` | 5 | Modify |
| `infrastructure/adapter/in/web/DocumentIntakeController.java` | 6 | Modify |
| `infrastructure/config/camel/CamelConfig.java` | 6 | Modify |
| `pom.xml` | 6 | Modify |
| `src/main/resources/application.yml` | 6 | Modify |
| `src/test/.../dto/event/EventStatusTest.java` | 2 | Delete |
| `src/test/.../usecase/DocumentIntakeServiceTest.java` | 2, 3, 4 | Modify |
| `src/test/.../web/DocumentIntakeControllerTest.java` | 6 | Modify |
| `src/test/.../config/camel/CamelConfigTest.java` | 6 | Modify |
| `src/test/.../integration/config/RestApiCdcTestConfiguration.java` | 6 | Modify |

All paths are relative to `src/main/java/com/wpanther/document/intake/` (main) and
`src/test/java/com/wpanther/document/intake/` (test).

---

## Task 1: Trivial Cleanups (Issues 6 & 7)

Remove three dead Jakarta Validation imports from `StartSagaCommand` and remove
`implements Serializable` from `ValidationResult`.

**Files:**
- Modify: `src/main/java/com/wpanther/document/intake/application/dto/event/StartSagaCommand.java`
- Modify: `src/main/java/com/wpanther/document/intake/domain/model/ValidationResult.java`

- [ ] **Step 1: Remove dead imports from StartSagaCommand**

Open `application/dto/event/StartSagaCommand.java`. Delete these three lines:
```java
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
```
No other changes. The field `DOCUMENT_TYPE_PATTERN` and the class body are untouched.

- [ ] **Step 2: Remove `implements Serializable` from ValidationResult**

Open `domain/model/ValidationResult.java`. Change line 9 from:
```java
public record ValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings
) implements Serializable {
```
to:
```java
public record ValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings
) {
```
Also remove the import `import java.io.Serializable;` at line 3.

- [ ] **Step 3: Run tests**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/document-intake-service
mvn test -q
```
Expected: BUILD SUCCESS. No tests reference `Serializable` or the removed imports.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wpanther/document/intake/application/dto/event/StartSagaCommand.java \
        src/main/java/com/wpanther/document/intake/domain/model/ValidationResult.java
git commit -m "refactor: remove dead imports and Serializable from domain record"
```

---

## Task 2: Delete EventStatus (Issue 5)

`EventStatus` duplicates `DocumentStatus` with identical string values. Four call sites
in the application service and several test assertions use it — all replaced by
`document.getStatus().name()` or raw string literals.

**Files:**
- Modify: `src/main/java/com/wpanther/document/intake/application/usecase/DocumentIntakeApplicationService.java`
- Delete: `src/main/java/com/wpanther/document/intake/application/dto/event/EventStatus.java`
- Modify: `src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java`
- Delete: `src/test/java/com/wpanther/document/intake/application/dto/event/EventStatusTest.java`

- [ ] **Step 1: Update DocumentIntakeApplicationService — replace EventStatus call sites**

In `application/usecase/DocumentIntakeApplicationService.java`:

Remove the import:
```java
import com.wpanther.document.intake.application.dto.event.EventStatus;
```

There are four `.status(EventStatus.X.getValue())` call sites. Replace each:

| Old | New |
|-----|-----|
| `.status(EventStatus.RECEIVED.getValue())` | `.status(document.getStatus().name())` |
| `.status(EventStatus.VALIDATED.getValue())` | `.status(document.getStatus().name())` |
| `.status(EventStatus.FORWARDED.getValue())` | `.status(document.getStatus().name())` |
| `.status(EventStatus.INVALID.getValue())` | `.status(document.getStatus().name())` |

Each `DocumentReceivedTraceEvent.builder()` call site already has access to `document`
at that point in the flow, so the substitution is direct.

- [ ] **Step 2: Update DocumentIntakeServiceTest — replace EventStatus assertions**

In `src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java`:

Remove the import:
```java
import com.wpanther.document.intake.application.dto.event.EventStatus;
```

Replace the three `EventStatus` assertion lines (search for `EventStatus` in the file):
```java
// Before
assertThat(events.get(0).getStatus()).isEqualTo(EventStatus.RECEIVED.getValue());
assertThat(events.get(1).getStatus()).isEqualTo(EventStatus.VALIDATED.getValue());
assertThat(events.get(2).getStatus()).isEqualTo(EventStatus.FORWARDED.getValue());
// (and the INVALID assertion in the invalid-doc test)
assertThat(events.get(1).getStatus()).isEqualTo(EventStatus.INVALID.getValue());

// After
assertThat(events.get(0).getStatus()).isEqualTo("RECEIVED");
assertThat(events.get(1).getStatus()).isEqualTo("VALIDATED");
assertThat(events.get(2).getStatus()).isEqualTo("FORWARDED");
assertThat(events.get(1).getStatus()).isEqualTo("INVALID");
```

- [ ] **Step 3: Run tests to confirm changes compile and pass**

```bash
mvn test -q
```
Expected: BUILD SUCCESS. (`EventStatus` still exists on disk; tests pass without it.)

- [ ] **Step 4: Delete EventStatus.java and EventStatusTest.java**

```bash
rm src/main/java/com/wpanther/document/intake/application/dto/event/EventStatus.java
rm src/test/java/com/wpanther/document/intake/application/dto/event/EventStatusTest.java
```

- [ ] **Step 5: Run tests again to confirm deletion causes no failures**

```bash
mvn test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete EventStatus, use DocumentStatus.name() directly"
```

---

## Task 3: DuplicateDocumentException (Issue 1)

Replace `DataIntegrityViolationException` (Spring type) in the application service with a
domain exception. The pre-check path also switches from `IllegalStateException` to the new
domain type.

**Files:**
- Create: `src/main/java/com/wpanther/document/intake/domain/exception/DuplicateDocumentException.java`
- Modify: `src/main/java/com/wpanther/document/intake/application/usecase/DocumentIntakeApplicationService.java`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/persistence/JpaDocumentRepository.java`
- Modify: `src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java`

- [ ] **Step 1: Create DuplicateDocumentException**

Create `src/main/java/com/wpanther/document/intake/domain/exception/DuplicateDocumentException.java`:
```java
package com.wpanther.document.intake.domain.exception;

public class DuplicateDocumentException extends RuntimeException {

    private final String documentNumber;

    private static final String MESSAGE_SUFFIX =
        ". A document with this number has already been submitted. " +
        "Please check existing documents or use a different document number.";

    public DuplicateDocumentException(String documentNumber) {
        super("Document number already exists: " + documentNumber + MESSAGE_SUFFIX);
        this.documentNumber = documentNumber;
    }

    public DuplicateDocumentException(String documentNumber, Throwable cause) {
        super("Document number already exists: " + documentNumber + MESSAGE_SUFFIX, cause);
        this.documentNumber = documentNumber;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }
}
```

- [ ] **Step 2: Update DocumentIntakeServiceTest — update duplicate assertion**

In `DocumentIntakeServiceTest`, find `testSubmitInvoiceWithDuplicateInvoiceNumber` and change the assertion:
```java
// Before
assertThatThrownBy(() -> documentIntakeService.submitDocument(VALID_XML, DEFAULT_SOURCE, "corr-123"))
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("already exists")
    .hasMessageContaining(TEST_DOCUMENT_NUMBER);

// After
assertThatThrownBy(() -> documentIntakeService.submitDocument(VALID_XML, DEFAULT_SOURCE, "corr-123"))
    .isInstanceOf(DuplicateDocumentException.class)
    .hasMessageContaining(TEST_DOCUMENT_NUMBER);
```

Add the import at the top of the test file:
```java
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
mvn test -Dtest=DocumentIntakeServiceTest#testSubmitInvoiceWithDuplicateInvoiceNumber -q
```
Expected: FAIL — `IllegalStateException` is not an instance of `DuplicateDocumentException`.

- [ ] **Step 4: Update DocumentIntakeApplicationService — pre-check throw**

In `application/usecase/DocumentIntakeApplicationService.java`:

Add import:
```java
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
```

Remove import:
```java
import org.springframework.dao.DataIntegrityViolationException;
```

Change the pre-check block (around line 128–136):
```java
// Before
if (documentRepository.existsByDocumentNumber(documentNumber)) {
    log.warn("Document number {} already exists", documentNumber);
    metrics.incrementFailed("duplicate_document_number");
    throw new IllegalStateException(
        "Document number already exists: " + documentNumber + ". " + ...
    );
}

// After
if (documentRepository.existsByDocumentNumber(documentNumber)) {
    log.warn("Document number {} already exists", documentNumber);
    metrics.incrementFailed("duplicate_document_number");
    throw new DuplicateDocumentException(documentNumber);
}
```

Remove the race-condition try-catch block entirely (around line 148–158).
`JpaDocumentRepository.save()` now throws `DuplicateDocumentException` directly (Step 5),
so the exception propagates naturally — no catch needed in the application service:
```java
// Before
try {
    document = documentRepository.save(document);
} catch (DataIntegrityViolationException e) {
    log.warn("Concurrent duplicate document number detected on save: {}", documentNumber);
    metrics.incrementFailed("concurrent_duplicate");
    throw new IllegalStateException(
        "Document number already exists: " + documentNumber + ". " + ...
    );
}

// After
document = documentRepository.save(document);
```

**Accepted metrics loss:** `metrics.incrementFailed("concurrent_duplicate")` is intentionally not preserved. After this refactor both duplicate paths (pre-check and concurrent) throw `DuplicateDocumentException` — the application service can no longer distinguish them at the catch site. The pre-check counter (`"duplicate_document_number"`) survives. If the concurrent path needs its own counter in future, it belongs in `JpaDocumentRepository.save()` which is the only remaining catch site, but that would require injecting the metrics port into the repository — scope beyond this task.

- [ ] **Step 5: Update JpaDocumentRepository — catch Spring exception, throw domain exception**

In `infrastructure/adapter/out/persistence/JpaDocumentRepository.java`:

Add import:
```java
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import org.springframework.dao.DataIntegrityViolationException;
```

Change the `save()` method:
```java
@Override
public IncomingDocument save(IncomingDocument document) {
    log.debug("Saving document: {}", document.getId());
    try {
        IncomingDocumentEntity entity = toEntity(document);
        IncomingDocumentEntity savedEntity = jpaRepository.save(entity);
        return toDomain(savedEntity);
    } catch (DataIntegrityViolationException e) {
        throw new DuplicateDocumentException(document.getDocumentNumber(), e);
    }
}
```

- [ ] **Step 6: Run tests**

```bash
mvn test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/wpanther/document/intake/domain/exception/DuplicateDocumentException.java \
        src/main/java/com/wpanther/document/intake/application/usecase/DocumentIntakeApplicationService.java \
        src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/persistence/JpaDocumentRepository.java \
        src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java
git commit -m "refactor: introduce DuplicateDocumentException, keep Spring exception in persistence adapter"
```

---

## Task 4: Normalize Port — Move XML Infrastructure to Adapter (Issue 2)

Add `normalize()` to `XmlValidationPort`. Move the ~90 lines of DOM/XSLT infrastructure
from `DocumentIntakeApplicationService` into `TedaXmlValidationAdapter`.

**Files:**
- Modify: `src/main/java/com/wpanther/document/intake/application/port/out/XmlValidationPort.java`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/validation/TedaXmlValidationAdapter.java`
- Modify: `src/main/java/com/wpanther/document/intake/application/usecase/DocumentIntakeApplicationService.java`
- Modify: `src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java`

- [ ] **Step 1: Add `normalize()` to XmlValidationPort**

In `application/port/out/XmlValidationPort.java`, add the method as the first declaration:
```java
package com.wpanther.document.intake.application.port.out;

import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.ValidationResult;

public interface XmlValidationPort {
    String normalize(String xmlContent);
    ValidationResult validate(String xmlContent);
    String extractDocumentNumber(String xmlContent);
    DocumentType extractDocumentType(String xmlContent);
}
```

- [ ] **Step 2: Update DocumentIntakeServiceTest — stub normalize() and fix two broken tests**

In `DocumentIntakeServiceTest.setUp()`, add a stub for the new method after the existing stubs:
```java
// Add after the existing validationService stubs
when(validationService.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
```

**Fix `testSubmitInvoiceWithNullXml` (will fail without this):** After Task 4 the service calls `normalize(null)` first. Mockito's `any()` matches null, so the setUp stub returns null — `extractDocumentNumber` is then called and the test's `never()` assertion fails. Add a null-specific override inside the test and fix the stale comment:
```java
@Test
@DisplayName("Submit document with null XML throws IllegalArgumentException before validation")
void testSubmitInvoiceWithNullXml() {
    // normalize() throws IAE for null (same contract as the adapter implementation)
    when(validationService.normalize(isNull()))
        .thenThrow(new IllegalArgumentException("xmlContent must not be null"));

    assertThatThrownBy(() -> documentIntakeService.submitDocument(null, DEFAULT_SOURCE, "corr-123"))
        .isInstanceOf(IllegalArgumentException.class);

    // normalize() throws IllegalArgumentException before extractDocumentNumber is called
    verify(validationService, never()).extractDocumentNumber(any());
    verify(documentRepository, never()).save(any());
}
```
Add `import static org.mockito.ArgumentMatchers.isNull;` to the test file's imports.

**Rewrite `testSubmitDocumentNormalizesXmlContent` (will fail without this):** The test currently captures the argument passed to `extractDocumentNumber` and asserts whitespace was stripped. With the no-op normalize stub, the captor receives the original indented XML and the content assertions fail. Replace the test to verify the service delegates to the port — actual normalization correctness belongs in a `TedaXmlValidationAdapter` test:
```java
@Test
@DisplayName("Submit document delegates XML normalization to validation port")
void testSubmitDocumentNormalizesXmlContent() {
    String indentedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rsm:TaxInvoice_CrossIndustryInvoice
            xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
            xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2">
            <rsm:ExchangedDocument>
                <ram:ID>NORM-001</ram:ID>
            </rsm:ExchangedDocument>
        </rsm:TaxInvoice_CrossIndustryInvoice>
        """;

    documentIntakeService.submitDocument(indentedXml, DEFAULT_SOURCE, "corr-norm");

    // The application service must delegate normalisation to the port
    verify(validationService).normalize(indentedXml);
}
```

- [ ] **Step 3: Verify compilation fails (adapter doesn't implement normalize() yet)**

```bash
mvn test -q 2>&1 | grep -E "error:|ERROR"
```
Expected: compilation error — `TedaXmlValidationAdapter` does not yet implement `normalize()`, so the whole module fails to compile. This is a compilation error, not a test failure. Proceed to Step 4 to add the implementation.

- [ ] **Step 4: Implement `normalize()` in TedaXmlValidationAdapter**

In `TedaXmlValidationAdapter`, add the following fields to the class (alongside the existing fields near line 62):
```java
private static final javax.xml.parsers.DocumentBuilderFactory XML_DBF;
private static final javax.xml.transform.TransformerFactory XML_TF;

static {
    XML_DBF = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    XML_DBF.setNamespaceAware(true);
    try {
        XML_DBF.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    } catch (javax.xml.parsers.ParserConfigurationException e) {
        throw new ExceptionInInitializerError("Failed to configure secure XML parser: " + e.getMessage());
    }
    XML_TF = javax.xml.transform.TransformerFactory.newInstance();
    try {
        XML_TF.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        XML_TF.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    } catch (IllegalArgumentException ignored) {
        // Some XSLT implementations do not support these attributes
    }
}
```

Add the `normalize()` method implementation before the existing `validate()` method:
```java
@Override
public String normalize(String xmlContent) {
    if (xmlContent == null) throw new IllegalArgumentException("xmlContent must not be null");
    if (xmlContent.isBlank()) return xmlContent;
    try {
        org.w3c.dom.Document doc = XML_DBF.newDocumentBuilder()
            .parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent.strip())));
        stripWhitespaceOnlyTextNodes(doc);
        javax.xml.transform.Transformer transformer = XML_TF.newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
        java.io.StringWriter out = new java.io.StringWriter();
        transformer.transform(
            new javax.xml.transform.dom.DOMSource(doc),
            new javax.xml.transform.stream.StreamResult(out));
        return out.toString();
    } catch (IllegalArgumentException e) {
        throw e;
    } catch (Exception e) {
        throw new IllegalArgumentException("XML normalization failed: invalid XML content", e);
    }
}

private static void stripWhitespaceOnlyTextNodes(org.w3c.dom.Node node) {
    java.util.List<org.w3c.dom.Node> toRemove = new java.util.ArrayList<>();
    org.w3c.dom.NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
        org.w3c.dom.Node child = children.item(i);
        if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                && child.getNodeValue().strip().isEmpty()) {
            toRemove.add(child);
        } else {
            stripWhitespaceOnlyTextNodes(child);
        }
    }
    toRemove.forEach(node::removeChild);
}
```

- [ ] **Step 5: Remove XML infrastructure from DocumentIntakeApplicationService**

In `DocumentIntakeApplicationService`, remove:
1. The two static fields: `private static final javax.xml.parsers.DocumentBuilderFactory XML_DBF;` and `private static final javax.xml.transform.TransformerFactory XML_TF;`
2. The entire `static { ... }` initializer block (~15 lines)
3. The `normalizeXml(String xmlContent)` method (~20 lines)
4. The `stripWhitespaceOnlyTextNodes(Node node)` method (~12 lines)

Change the first line of `submitDocument()` from:
```java
xmlContent = normalizeXml(xmlContent);
```
to:
```java
xmlContent = validationService.normalize(xmlContent);
```

No import changes needed — `validationService` is already `XmlValidationPort` which now declares `normalize()`.

- [ ] **Step 6: Run tests**

```bash
mvn test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/wpanther/document/intake/application/port/out/XmlValidationPort.java \
        src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/validation/TedaXmlValidationAdapter.java \
        src/main/java/com/wpanther/document/intake/application/usecase/DocumentIntakeApplicationService.java \
        src/test/java/com/wpanther/document/intake/application/usecase/DocumentIntakeServiceTest.java
git commit -m "refactor: move XML normalization to TedaXmlValidationAdapter, expose via port"
```

---

## Task 5: OutboxHealthPort (Issue 3)

Create a local `OutboxHealthStatus` enum and `OutboxHealthPort` interface. Implement the
port in `JpaOutboxEventRepository` with translation from `OutboxStatus` (saga-commons).
Inject the port into `OutboxHealthIndicator` instead of `SpringDataOutboxRepository`.

**Files:**
- Create: `src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthStatus.java`
- Create: `src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthPort.java`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/health/OutboxHealthIndicator.java`

- [ ] **Step 1: Create OutboxHealthStatus enum**

Create `src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthStatus.java`:
```java
package com.wpanther.document.intake.application.port.out;

public enum OutboxHealthStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
```

- [ ] **Step 2: Create OutboxHealthPort interface**

Create `src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthPort.java`:
```java
package com.wpanther.document.intake.application.port.out;

public interface OutboxHealthPort {
    long countByStatus(OutboxHealthStatus status);
}
```

- [ ] **Step 3: Implement OutboxHealthPort in JpaOutboxEventRepository**

In `infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java`:

Change the class declaration to add the new interface:
```java
public class JpaOutboxEventRepository implements OutboxEventRepository, OutboxHealthPort {
```

Add the import:
```java
import com.wpanther.document.intake.application.port.out.OutboxHealthPort;
import com.wpanther.document.intake.application.port.out.OutboxHealthStatus;
```

Add the implementation method at the end of the class (before the closing `}`):
```java
@Override
public long countByStatus(OutboxHealthStatus status) {
    OutboxStatus sagaStatus = switch (status) {
        case PENDING   -> OutboxStatus.PENDING;
        case PUBLISHED -> OutboxStatus.PUBLISHED;
        case FAILED    -> OutboxStatus.FAILED;
    };
    return springRepository.countByStatus(sagaStatus);
}
```

- [ ] **Step 4: Update OutboxHealthIndicator — inject OutboxHealthPort**

Replace the full `OutboxHealthIndicator` class content:
```java
package com.wpanther.document.intake.infrastructure.adapter.out.health;

import com.wpanther.document.intake.application.port.out.OutboxHealthPort;
import com.wpanther.document.intake.application.port.out.OutboxHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OutboxHealthIndicator.class);

    private final OutboxHealthPort outboxHealthPort;
    private final long pendingThreshold;

    public OutboxHealthIndicator(
            OutboxHealthPort outboxHealthPort,
            @Value("${app.outbox.pending-threshold:100}") long pendingThreshold) {
        this.outboxHealthPort = outboxHealthPort;
        this.pendingThreshold = pendingThreshold;
    }

    @Override
    @Transactional(readOnly = true)
    public Health health() {
        try {
            long failedCount  = outboxHealthPort.countByStatus(OutboxHealthStatus.FAILED);
            long pendingCount = outboxHealthPort.countByStatus(OutboxHealthStatus.PENDING);

            if (failedCount > 0) {
                log.warn("Outbox health: {} failed event(s) require manual intervention", failedCount);
                return Health.down()
                        .withDetail("failedEvents", failedCount)
                        .withDetail("pendingEvents", pendingCount)
                        .withDetail("message", "Outbox has " + failedCount + " failed event(s) requiring manual intervention")
                        .build();
            }

            if (pendingCount >= pendingThreshold) {
                log.warn("Outbox health: pending backlog {} exceeds threshold {}", pendingCount, pendingThreshold);
                return Health.outOfService()
                        .withDetail("failedEvents", failedCount)
                        .withDetail("pendingEvents", pendingCount)
                        .withDetail("message", "Outbox pending backlog (" + pendingCount + ") exceeds threshold (" + pendingThreshold + "). Check Debezium CDC connector.")
                        .build();
            }

            return Health.up()
                    .withDetail("failedEvents", failedCount)
                    .withDetail("pendingEvents", pendingCount)
                    .build();

        } catch (Exception e) {
            log.error("Outbox health check failed — database unreachable", e);
            return Health.down()
                    .withDetail("message", "Database unreachable: " + e.getMessage())
                    .withException(e)
                    .build();
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
mvn test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthStatus.java \
        src/main/java/com/wpanther/document/intake/application/port/out/OutboxHealthPort.java \
        src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java \
        src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/health/OutboxHealthIndicator.java
git commit -m "refactor: introduce OutboxHealthPort, remove SpringDataOutboxRepository from health indicator"
```

---

## Task 6: Remove Camel from REST Adapter, Add Resilience4j (Issue 4)

Remove `ProducerTemplate` and `CamelExecutionException` from the controller. The
controller calls the use case directly. Rate limiting is provided by Resilience4j's
`@RateLimiter` AOP annotation. The Kafka Camel route is preserved.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/adapter/in/web/DocumentIntakeController.java`
- Modify: `src/main/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfig.java`
- Modify: `src/test/java/com/wpanther/document/intake/infrastructure/adapter/in/web/DocumentIntakeControllerTest.java`
- Modify: `src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigTest.java`
- Modify: `src/test/java/com/wpanther/document/intake/integration/config/RestApiCdcTestConfiguration.java`

- [ ] **Step 1: Add Resilience4j to pom.xml**

In `pom.xml`, in the `<properties>` section, add:
```xml
<resilience4j.version>2.2.0</resilience4j.version>
```

In the `<dependencies>` section (after the `micrometer-registry-prometheus` dependency), add:
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>${resilience4j.version}</version>
</dependency>
```

- [ ] **Step 2: Add Resilience4j rate limiter config to application.yml**

In `src/main/resources/application.yml`, add a new top-level section (after the existing `management:` block):
```yaml
resilience4j:
  ratelimiter:
    instances:
      documentIntake:
        limit-for-period: ${app.rate-limit.requests-per-second:10}
        limit-refresh-period: "${app.rate-limit.time-period-seconds:60}s"
        timeout-duration: 0s
```

Note: `limit-refresh-period` uses string interpolation to append `s` (seconds suffix),
forming a valid Spring Duration string. Default: 10 requests per 60-second window.

- [ ] **Step 3: Rewrite DocumentIntakeControllerTest**

Replace the full content of `DocumentIntakeControllerTest.java`:
```java
package com.wpanther.document.intake.infrastructure.adapter.in.web;

import com.wpanther.document.intake.application.usecase.GetDocumentUseCase;
import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.model.DocumentStatus;
import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.domain.model.ValidationResult;
import com.wpanther.document.intake.infrastructure.config.validation.ValidationProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DocumentIntakeController.class,
    properties = "app.security.enabled=false",
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
    })
@DisplayName("Document Intake Controller Tests")
class DocumentIntakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubmitDocumentUseCase submitDocumentUseCase;

    @MockBean
    private GetDocumentUseCase getDocumentUseCase;

    @MockBean
    private ValidationProperties validationProperties;

    private IncomingDocument testDocument;

    @BeforeEach
    void setUp() {
        when(validationProperties.getMaxXmlSize()).thenReturn(10485760L);
        when(validationProperties.getMaxXmlSizeMb()).thenReturn(10);
        when(validationProperties.getMaxXmlDepth()).thenReturn(100);
        when(validationProperties.getMaxElementCount()).thenReturn(10000);

        testDocument = IncomingDocument.builder()
            .id(UUID.randomUUID())
            .documentNumber("INV-2024-001")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .correlationId("corr-123")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.VALIDATED)
            .validationResult(ValidationResult.success())
            .receivedAt(Instant.now())
            .build();
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 202 Accepted for valid document")
    void testSubmitInvoiceReturns202Accepted() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), eq("corr-123")))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-123"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value("corr-123"));
    }

    @Test
    @DisplayName("POST /api/v1/documents generates correlation ID when not provided")
    void testSubmitInvoiceGeneratesCorrelationId() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 400 for invalid XML")
    void testSubmitInvoiceReturns400ForInvalidXml() throws Exception {
        doThrow(new IllegalArgumentException("Could not extract document number"))
            .when(submitDocumentUseCase).submitDocument(any(), any(), any());

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("invalid xml"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid document"));
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 409 for duplicate document number")
    void testSubmitInvoiceReturns409ForDuplicateDocument() throws Exception {
        doThrow(new DuplicateDocumentException("INV-2024-001"))
            .when(submitDocumentUseCase).submitDocument(any(), any(), any());

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-dup"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Document already exists"));
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 413 when payload too large")
    void testSubmitInvoiceReturns413WhenPayloadTooLarge() throws Exception {
        when(validationProperties.getMaxXmlSize()).thenReturn(5L); // very small limit

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("Payload too large"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 200 OK when document exists")
    void testGetInvoiceByIdReturns200() throws Exception {
        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(testDocument);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentNumber").value("INV-2024-001"))
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.documentType").value("TAX_INVOICE"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 404 when document not found")
    void testGetInvoiceByIdReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(getDocumentUseCase.getDocument(unknownId))
            .thenThrow(new IllegalArgumentException("Document not found: " + unknownId));

        mockMvc.perform(get("/api/v1/documents/{id}", unknownId))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns validation result")
    void testGetInvoiceIncludesValidationResult() throws Exception {
        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(testDocument);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationResult.valid").value(true));
    }

    @Test
    @DisplayName("rateLimitFallback returns 429 Too Many Requests")
    void testRateLimitFallbackReturns429() {
        DocumentIntakeController controller = new DocumentIntakeController(
            submitDocumentUseCase, validationProperties, getDocumentUseCase);
        RequestNotPermitted ex = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("test"));

        ResponseEntity<Map<String, Object>> response =
            controller.rateLimitFallback("<xml/>", null, ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).isEqualTo("Rate limit exceeded");
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 400 for blank body (@NotBlank violation)")
    void testSubmitInvoiceReturns400ForBlankBody() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/v1/documents accepts text/xml content type")
    void testSubmitInvoiceAcceptsTextXmlContentType() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.TEXT_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-text-xml"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value("corr-text-xml"));
    }

    @Test
    @DisplayName("POST /api/v1/documents with no X-Correlation-ID generates UUID")
    void testSubmitInvoiceWithNullCorrelationIdGeneratesUuid() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").isString())
            .andExpect(jsonPath("$.correlationId").value(
                org.hamcrest.Matchers.matchesPattern(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    @DisplayName("POST /api/v1/documents handles empty X-Correlation-ID header")
    void testSubmitInvoiceHandlesEmptyCorrelationId() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", ""))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /api/v1/documents ignores X-Source header (source is always REST)")
    void testSubmitInvoiceIgnoresSourceHeader() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Source", "KAFKA"))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document with null documentType")
    void testGetInvoiceHandlesNullDocumentType() throws Exception {
        IncomingDocument documentWithNullType = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-003")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(null)
            .status(DocumentStatus.VALIDATING)
            .validationResult(ValidationResult.success())
            .receivedAt(java.time.Instant.now())
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithNullType);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VALIDATING"))
            .andExpect(jsonPath("$.documentType").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document without processedAt")
    void testGetInvoiceHandlesNullProcessedAt() throws Exception {
        IncomingDocument documentWithoutProcessedAt = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-004")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.RECEIVED)
            .validationResult(ValidationResult.success())
            .receivedAt(java.time.Instant.now())
            .processedAt(null)
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithoutProcessedAt);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.receivedAt").exists())
            .andExpect(jsonPath("$.processedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document without validation result")
    void testGetInvoiceHandlesNullValidationResult() throws Exception {
        IncomingDocument documentWithoutValidation = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-005")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.RECEIVED)
            .validationResult(null)
            .receivedAt(java.time.Instant.now())
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithoutValidation);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationResult").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 500 for unexpected errors")
    void testGetInvoiceReturns500ForUnexpectedErrors() throws Exception {
        UUID testId = UUID.randomUUID();
        when(getDocumentUseCase.getDocument(testId))
            .thenThrow(new RuntimeException("Database connection failed"));

        mockMvc.perform(get("/api/v1/documents/{id}", testId))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Failed to retrieve document status"));
    }
}
```

- [ ] **Step 4: Run controller tests to verify they fail (controller still uses Camel)**

```bash
mvn test -Dtest=DocumentIntakeControllerTest -q 2>&1 | tail -20
```
Expected: FAIL — `ProducerTemplate` is no longer a `@MockBean` so controller context will fail to start, or the test stubs won't match.

- [ ] **Step 5: Rewrite DocumentIntakeController**

Replace the full content of `DocumentIntakeController.java`:
```java
package com.wpanther.document.intake.infrastructure.adapter.in.web;

import com.wpanther.document.intake.application.usecase.GetDocumentUseCase;
import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.infrastructure.config.validation.ValidationProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
@Validated
@Tag(name = "Document Intake", description = "API for submitting and retrieving Thai e-Tax XML documents")
public class DocumentIntakeController {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeController.class);

    private final SubmitDocumentUseCase submitDocumentUseCase;
    private final ValidationProperties validationProperties;
    private final GetDocumentUseCase getDocumentUseCase;

    public DocumentIntakeController(
            SubmitDocumentUseCase submitDocumentUseCase,
            ValidationProperties validationProperties,
            GetDocumentUseCase getDocumentUseCase) {
        this.submitDocumentUseCase = submitDocumentUseCase;
        this.validationProperties = validationProperties;
        this.getDocumentUseCase = getDocumentUseCase;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @RateLimiter(name = "documentIntake", fallbackMethod = "rateLimitFallback")
    @Operation(
        summary = "Submit a Thai e-Tax XML document",
        description = "Submit an XML document for validation and processing. " +
                      "Valid documents trigger a saga orchestration workflow."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Document accepted for processing",
            content = @Content(schema = @Schema(implementation = SubmitDocumentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid document content",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Document number already exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Payload too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> submitDocument(
        @Parameter(description = "Thai e-Tax XML document content", required = true)
        @RequestBody @NotBlank String xmlContent,
        @Parameter(description = "Optional correlation ID for distributed tracing")
        @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        if (xmlContent.length() > validationProperties.getMaxXmlSize()) {
            log.warn("Document rejected - size exceeds maximum: {} > {}",
                xmlContent.length(), validationProperties.getMaxXmlSize());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "Payload too large",
                "message", "XML content exceeds maximum size of " +
                    validationProperties.getMaxXmlSizeMb() + "MB"
            ));
        }

        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        log.info("Received document submission via REST, correlationId: {}", effectiveCorrelationId);

        try {
            submitDocumentUseCase.submitDocument(xmlContent, "REST", effectiveCorrelationId);
            return ResponseEntity.accepted().body(Map.of(
                "message", "Document submitted for processing",
                "correlationId", effectiveCorrelationId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Document rejected — invalid content: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid document",
                "message", e.getMessage()
            ));
        } catch (DuplicateDocumentException e) {
            log.warn("Document rejected — duplicate: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Document already exists",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error submitting document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to submit document",
                "message", e.getMessage()
            ));
        }
    }

    public ResponseEntity<Map<String, Object>> rateLimitFallback(
            String xmlContent, String correlationId, RequestNotPermitted ex) {
        log.warn("Rate limit exceeded for document submission");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
            "error", "Rate limit exceeded",
            "message", "Too many requests. Please retry after a moment."
        ));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document status",
        description = "Retrieve the current status and details of a submitted document by its UUID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Document found",
            content = @Content(schema = @Schema(implementation = DocumentStatusResponse.class))),
        @ApiResponse(responseCode = "404", description = "Document not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> getDocumentStatus(
        @Parameter(description = "Document UUID")
        @PathVariable UUID id) {
        try {
            IncomingDocument document = getDocumentUseCase.getDocument(id);

            Map<String, Object> response = new HashMap<>();
            response.put("id", document.getId().toString());
            response.put("documentNumber", document.getDocumentNumber());
            response.put("status", document.getStatus().name());

            if (document.getDocumentType() != null) {
                response.put("documentType", document.getDocumentType().name());
            }
            if (document.getReceivedAt() != null) {
                response.put("receivedAt", document.getReceivedAt().toString());
            }
            if (document.getProcessedAt() != null) {
                response.put("processedAt", document.getProcessedAt().toString());
            }
            if (document.getValidationResult() != null) {
                response.put("validationResult", Map.of(
                    "valid", document.getValidationResult().valid(),
                    "errors", document.getValidationResult().errors(),
                    "warnings", document.getValidationResult().warnings()
                ));
            }
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error retrieving document status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve document status"
            ));
        }
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .collect(Collectors.joining("; "));
        log.warn("Request constraint violation: {}", message);
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid request",
            "message", message
        ));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getAllErrors().stream()
            .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Validation failed")
            .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", message);
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid request",
            "message", message
        ));
    }

    @Schema(description = "Response returned when document is accepted")
    private static class SubmitDocumentResponse {
        @Schema(example = "Document submitted for processing") private String message;
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000") private String correlationId;
    }

    @Schema(description = "Document status and details")
    private static class DocumentStatusResponse {
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000") private String id;
        @Schema(example = "TAX-2025-001") private String documentNumber;
        @Schema(example = "VALIDATED") private String status;
        @Schema(example = "TAX_INVOICE") private String documentType;
        @Schema(example = "2025-01-15T10:30:00Z") private String receivedAt;
        @Schema(example = "2025-01-15T10:30:05Z") private String processedAt;
        @Schema private ValidationResultSchema validationResult;
    }

    @Schema(description = "Validation result details")
    private static class ValidationResultSchema {
        @Schema(example = "true") private boolean valid;
        @Schema(example = "[]") private java.util.List<String> errors;
        @Schema(example = "[]") private java.util.List<String> warnings;
    }

    @Schema(description = "Error response")
    private static class ErrorResponse {
        @Schema(example = "Invalid document") private String error;
        @Schema(example = "Document type could not be determined") private String message;
    }
}
```

- [ ] **Step 6: Update CamelConfig — remove direct: route and RateLimitProperties injection**

Replace the full content of `CamelConfig.java`:
```java
package com.wpanther.document.intake.infrastructure.config.camel;

import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for document intake.
 * <p>
 * The Kafka consumer route receives documents from Kafka and delegates to
 * {@link SubmitDocumentUseCase}. Dead-letter channel handles failures after retries.
 * <p>
 * The REST path no longer uses Camel — the controller calls the use case directly.
 */
@Component
@Slf4j
public class CamelConfig extends RouteBuilder {

    private final SubmitDocumentUseCase submitDocumentUseCase;
    private final String documentIntakeTopic;
    private final String intakeDlqTopic;
    private final boolean kafkaConsumerAutoStartup;

    public CamelConfig(
            SubmitDocumentUseCase submitDocumentUseCase,
            @Value("${app.kafka.topics.invoice-intake}") String documentIntakeTopic,
            @Value("${app.kafka.topics.intake-dlq}") String intakeDlqTopic,
            @Value("${app.kafka.consumer.auto-startup:true}") boolean kafkaConsumerAutoStartup) {
        this.submitDocumentUseCase = submitDocumentUseCase;
        this.documentIntakeTopic = documentIntakeTopic;
        this.intakeDlqTopic = intakeDlqTopic;
        this.kafkaConsumerAutoStartup = kafkaConsumerAutoStartup;
    }

    @Override
    public void configure() {
        if (kafkaConsumerAutoStartup) {
            from("kafka:" + documentIntakeTopic + "?groupId=intake-service&autoCommitEnable=false")
                .errorHandler(deadLetterChannel("kafka:" + intakeDlqTopic)
                    .maximumRedeliveries(3)
                    .redeliveryDelay(1000)
                    .useExponentialBackOff()
                    .logExhausted(true))
                .routeId("document-intake-kafka")
                .log(LoggingLevel.INFO, "Received document from Kafka: ${header[kafka.KEY]}")
                .process(exchange -> {
                    String xmlContent = exchange.getIn().getBody(String.class);
                    String correlationId = exchange.getIn().getHeader("kafka.KEY", String.class);

                    IncomingDocument document = submitDocumentUseCase.submitDocument(
                        xmlContent, "KAFKA", correlationId);

                    exchange.getIn().setHeader("documentId", document.getId().toString());
                    exchange.getIn().setHeader("documentType", document.getDocumentType().name());
                    exchange.getIn().setHeader("isValid", document.isValid());
                })
                .log(LoggingLevel.INFO, "Document processed: documentId=${header.documentId}, isValid=${header.isValid}");
        }
    }
}
```

- [ ] **Step 7: Update CamelConfigTest — remove direct: route tests**

Replace the full content of `CamelConfigTest.java`:
```java
package com.wpanther.document.intake.infrastructure.config.camel;

import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying CamelConfig initialises cleanly.
 * The direct:document-intake route has been removed; the controller calls the use case directly.
 * The Kafka consumer route is disabled in this test via auto-startup=false.
 * Kafka route behaviour (DLQ, retry, document processing) is covered by DocumentIntakeCdcIT.
 */
@CamelSpringBootTest
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    KafkaAutoConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.kafka.topics.invoice-intake=document.intake",
    "app.kafka.topics.intake-dlq=document.intake.dlq",
    "app.kafka.bootstrap-servers=localhost:9092",
    "app.kafka.consumer.auto-startup=false",
    "app.rate-limit.enabled=false",
    "camel.springboot.main-run-controller=false",
    "camel.springboot.xml-routes=false"
})
@ComponentScan(basePackages = {
    "com.wpanther.document.intake.infrastructure.config.camel"
})
@DisplayName("CamelConfig Integration Tests")
class CamelConfigTest {

    @Autowired
    private CamelContext camelContext;

    @MockBean
    private SubmitDocumentUseCase submitDocumentUseCase;

    @Test
    @DisplayName("CamelContext starts successfully with no routes (Kafka disabled)")
    void testCamelContextStarts() {
        assertThat(camelContext).isNotNull();
        assertThat(camelContext.isStarted()).isTrue();
    }
}
```

- [ ] **Step 8: Update RestApiCdcTestConfiguration — remove ProducerTemplate comments and dependency**

In `RestApiCdcTestConfiguration.java`, update the Javadoc comment block (lines 15–27) to:
```java
/**
 * Test configuration for REST API CDC integration tests.
 * <p>
 * Excludes KafkaAutoConfiguration to prevent Spring from auto-creating Kafka consumer beans.
 * The Kafka consumer is also disabled via {@code app.kafka.consumer.auto-startup=false}.
 * <p>
 * CamelAutoConfiguration is included so that the Kafka Camel route definition is loaded
 * (even though the consumer is disabled). The REST controller calls the use case directly
 * and no longer depends on ProducerTemplate or the direct:document-intake route.
 */
```

No changes to the `@EnableAutoConfiguration`, `@ComponentScan`, or any other annotation.

- [ ] **Step 9: Update RateLimitConfig Javadoc**

In `infrastructure/config/ratelimit/RateLimitConfig.java`, replace the stale Javadoc comment
(lines 9–22) that says `CamelConfig unconditionally requires it for throttle configuration`:
```java
/**
 * Rate limiting configuration for the document intake REST endpoint.
 * <p>
 * Registers {@link RateLimitProperties} as a configuration-properties bean so its values
 * ({@code app.rate-limit.requests-per-second}, {@code app.rate-limit.time-period-seconds})
 * are available for Spring property interpolation in {@code application.yml}, where they
 * feed the Resilience4j rate-limiter instance configuration:
 * <pre>
 * resilience4j.ratelimiter.instances.documentIntake.limit-for-period:
 *     ${app.rate-limit.requests-per-second:10}
 * resilience4j.ratelimiter.instances.documentIntake.limit-refresh-period:
 *     "${app.rate-limit.time-period-seconds:60}s"
 * </pre>
 * Rate limiting can be disabled by setting {@code app.rate-limit.enabled=false} in
 * application properties (the Resilience4j bean still loads; no requests will be
 * throttled if the rate limiter is not applied via annotation).
 */
```

- [ ] **Step 10: Run all tests**

```bash
mvn test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add pom.xml \
        src/main/resources/application.yml \
        src/main/java/com/wpanther/document/intake/infrastructure/adapter/in/web/DocumentIntakeController.java \
        src/main/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfig.java \
        src/main/java/com/wpanther/document/intake/infrastructure/config/ratelimit/RateLimitConfig.java \
        src/test/java/com/wpanther/document/intake/infrastructure/adapter/in/web/DocumentIntakeControllerTest.java \
        src/test/java/com/wpanther/document/intake/infrastructure/config/camel/CamelConfigTest.java \
        src/test/java/com/wpanther/document/intake/integration/config/RestApiCdcTestConfiguration.java
git commit -m "refactor: remove Camel from REST path, add Resilience4j rate limiter"
```

---

## Final Verification

- [ ] **Run the full test suite**

```bash
mvn verify -q
```
Expected: BUILD SUCCESS. JaCoCo coverage check passes (90% line coverage requirement).

- [ ] **Verify no remaining layer violations**

```bash
grep -r "DataIntegrityViolationException" src/main/java/com/wpanther/document/intake/application/ && echo "VIOLATION FOUND" || echo "Clean"
grep -r "DocumentBuilderFactory\|TransformerFactory" src/main/java/com/wpanther/document/intake/application/ && echo "VIOLATION FOUND" || echo "Clean"
grep -r "SpringDataOutboxRepository" src/main/java/com/wpanther/document/intake/infrastructure/adapter/out/health/ && echo "VIOLATION FOUND" || echo "Clean"
grep -r "ProducerTemplate\|CamelExecutionException" src/main/java/com/wpanther/document/intake/infrastructure/adapter/in/web/ && echo "VIOLATION FOUND" || echo "Clean"
grep -r "EventStatus" src/main/java/com/wpanther/document/intake/application/ && echo "VIOLATION FOUND" || echo "Clean"
grep -r "Serializable" src/main/java/com/wpanther/document/intake/domain/model/ValidationResult.java && echo "VIOLATION FOUND" || echo "Clean"
```
Expected: all six checks print `Clean`.
