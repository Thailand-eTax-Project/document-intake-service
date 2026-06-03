# Integration Test Layout Design

**Date:** 2026-03-30
**Scope:** `document-intake-service` (template for all 12 microservices)

## Problem

`src/it/java` is a dead directory. Maven only compiles `src/main/java` and `src/test/java` by default. Without `build-helper-maven-plugin`, the CDC integration tests in `src/it/java` are never compiled and can never run. Additionally:

- `CamelConfigIntegrationTest` in `src/test/java` is `@Disabled` and excluded from surefire by name — a Spring context test that should run in normal `mvn test` is effectively orphaned.
- `application-cdc-test.yml` lives in `src/it/resources` but CLAUDE.md references `src/test/resources`.

## Decision

Adopt **standard Maven `src/test/java` with `*IT` naming** (Option B). This is the Maven community convention: surefire runs `*Test.java`; failsafe runs `*IT.java`.

## Test Classification

| Tier | Naming | Runner | When |
|------|--------|--------|------|
| Unit tests | `*Test.java` | Surefire | Always (`mvn test`) |
| Spring context tests (H2/mocks) | `*Test.java` | Surefire | Always (`mvn test`) |
| CDC integration tests (external containers) | `*IT.java` | Failsafe | Opt-in (`mvn verify -P integration`) |

CDC tests require live external containers (PostgreSQL :5433, Kafka :9093, Debezium :8083) started via `./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors`.

## File Layout Changes

### Moves and renames

```
src/it/java/.../integration/AbstractCdcIntegrationTest.java
  → src/test/java/.../integration/AbstractCdcIT.java

src/it/java/.../integration/DocumentIntakeCdcIntegrationTest.java
  → src/test/java/.../integration/DocumentIntakeCdcIT.java

src/it/java/.../integration/OutboxTableIntegrationTest.java
  → src/test/java/.../integration/OutboxTableIT.java

src/it/java/.../integration/config/CdcTestConfiguration.java
  → src/test/java/.../integration/config/CdcTestConfiguration.java

src/it/java/.../integration/config/TestKafkaConsumerConfig.java
  → src/test/java/.../integration/config/TestKafkaConsumerConfig.java

src/it/resources/application-cdc-test.yml
  → src/test/resources/application-cdc-test.yml
```

`src/it/` directory is deleted entirely.

### CamelConfigIntegrationTest

Rename to `CamelConfigTest`, remove `@Disabled`. This is a Spring context test using mocks — it belongs in normal `mvn test`. All test methods and logic stay unchanged.

## pom.xml Changes

### Surefire — remove the IntegrationTest exclusion

```xml
<!-- REMOVE this block entirely -->
<excludes>
    <exclude>**/*IntegrationTest.java</exclude>
</excludes>
```

### Failsafe — remove testSourceDirectory, update include pattern

```xml
<configuration>
    <skip>${skipITs}</skip>
    <includes>
        <include>**/*IT.java</include>
    </includes>
    <!-- testSourceDirectory removed — src/test/java is the default -->
</configuration>
```

`skipITs=true` default and `-P integration` profile remain unchanged. No `build-helper-maven-plugin` needed.

## Class-Level Changes

- `AbstractCdcIntegrationTest` → `AbstractCdcIT` (class + file rename only)
- `DocumentIntakeCdcIntegrationTest` → `DocumentIntakeCdcIT`, update `extends AbstractCdcIT`
- `OutboxTableIntegrationTest` → `OutboxTableIT`, update `extends AbstractCdcIT`
- `CamelConfigIntegrationTest` → `CamelConfigTest`: remove `@Disabled`, remove its import
- `application-cdc-test.yml`: no content changes

## Replication to Other Services

This is the standard template for all 12 microservices. For each service that gains CDC tests:

1. Name CDC test classes `*IT.java` in `src/test/java`
2. Apply the same surefire/failsafe `pom.xml` changes
3. Place CDC-specific config in `src/test/resources/application-cdc-test.yml`

No extra Maven plugins required.

## What Does NOT Change

- `skipITs=true` default (CDC tests never run unless explicitly opted in)
- `-P integration` profile activation
- All CDC test logic (`AbstractCdcIT`, container verification, Kafka polling utilities)
- All unit test logic
- H2 test configuration (`application-test.yml`)
