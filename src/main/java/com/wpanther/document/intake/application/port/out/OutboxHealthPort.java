package com.wpanther.document.intake.application.port.out;

public interface OutboxHealthPort {
    long countByStatus(OutboxHealthStatus status);
}
