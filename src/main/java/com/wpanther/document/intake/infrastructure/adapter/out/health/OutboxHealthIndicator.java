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
