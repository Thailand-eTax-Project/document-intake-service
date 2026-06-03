package com.wpanther.document.intake.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.document.intake.application.port.out.DocumentEventPublisher;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Publisher for integration events using the outbox pattern.
 * <p>
 * Translates domain objects into infrastructure message types and writes them to the
 * outbox table within the same transaction as domain state changes. Debezium CDC reads
 * the outbox table and publishes events to Kafka topics asynchronously, providing
 * guaranteed delivery.
 * <p>
 * Jackson-annotated message types ({@link StartSagaCommand}, {@link DocumentReceivedTraceEvent})
 * are constructed here — they do not appear in the application layer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher implements DocumentEventPublisher {

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStartSagaCommand(IncomingDocument document, String xmlContent) {
        StartSagaCommand command = StartSagaCommand.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType().name())
                .documentNumber(document.getDocumentNumber())
                .xmlContent(xmlContent)
                .correlationId(document.getCorrelationId())
                .source(document.getSource())
                .build();

        Map<String, String> headers = new HashMap<>();
        headers.put("documentType", command.getDocumentType());
        if (command.getCorrelationId() != null) {
            headers.put("correlationId", command.getCorrelationId());
        }

        String partitionKey = command.getCorrelationId() != null
                ? command.getCorrelationId()
                : command.getDocumentId();

        outboxService.saveWithRouting(
                command,
                "IncomingDocument",
                command.getDocumentId(),
                "saga.commands.orchestrator",
                partitionKey,
                toJson(headers)
        );

        log.info("Published StartSagaCommand for document: {}", document.getId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTraceEvent(IncomingDocument document) {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType() != null ? document.getDocumentType().name() : null)
                .documentNumber(document.getDocumentNumber())
                .correlationId(document.getCorrelationId())
                .status(document.getStatus().name())
                .source(document.getSource())
                .build();

        Map<String, String> headers = new HashMap<>();
        if (event.getDocumentType() != null) {
            headers.put("documentType", event.getDocumentType());
        }
        if (event.getCorrelationId() != null) {
            headers.put("correlationId", event.getCorrelationId());
        }

        String partitionKey = event.getCorrelationId() != null
                ? event.getCorrelationId()
                : event.getDocumentId();

        outboxService.saveWithRouting(
                event,
                "IncomingDocument",
                event.getDocumentId(),
                "trace.document.received",
                partitionKey,
                toJson(headers)
        );

        log.debug("Published DocumentReceivedTraceEvent for document: {} status: {}",
                document.getId(), document.getStatus());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers to JSON", e);
            return null;
        }
    }
}
