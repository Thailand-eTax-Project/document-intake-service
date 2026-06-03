package com.wpanther.document.intake.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;

import java.time.Instant;
import java.util.UUID;

/**
 * Command sent to orchestrator-service to start a new saga.
 * Published to topic: saga.commands.orchestrator
 * <p>
 * Infrastructure-layer message object — carries Jackson serialization annotations
 * required for the outbox → Kafka wire format. Constructed by {@link EventPublisher}
 * from domain types; the application layer has no direct dependency on this class.
 * <p>
 * {@code sagaId} and {@code sagaStep} are null on creation; the orchestrator-service
 * assigns them when it creates the saga instance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StartSagaCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    private final String documentId;
    private final String documentType;
    private final String documentNumber;
    private final String xmlContent;
    private final String source;

    private StartSagaCommand(
            String documentId,
            String documentType,
            String documentNumber,
            String xmlContent,
            String correlationId,
            String source) {
        super(null, null, correlationId);
        this.documentId = documentId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.xmlContent = xmlContent;
        this.source = source;
    }

    @JsonProperty("documentId")
    public String getDocumentId() { return documentId; }

    @JsonProperty("documentType")
    public String getDocumentType() { return documentType; }

    @JsonProperty("documentNumber")
    public String getDocumentNumber() { return documentNumber; }

    @JsonProperty("xmlContent")
    public String getXmlContent() { return xmlContent; }

    @JsonProperty("source")
    public String getSource() { return source; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String documentId;
        private String documentType;
        private String documentNumber;
        private String xmlContent;
        private String correlationId;
        private String source;

        public Builder documentId(String v)     { this.documentId = v;     return this; }
        public Builder documentType(String v)   { this.documentType = v;   return this; }
        public Builder documentNumber(String v) { this.documentNumber = v; return this; }
        public Builder xmlContent(String v)     { this.xmlContent = v;     return this; }
        public Builder correlationId(String v)  { this.correlationId = v;  return this; }
        public Builder source(String v)         { this.source = v;         return this; }

        public StartSagaCommand build() {
            return new StartSagaCommand(documentId, documentType, documentNumber,
                                        xmlContent, correlationId, source);
        }
    }

    @JsonCreator
    static StartSagaCommand fromJson(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("documentType") String documentType,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("xmlContent") String xmlContent,
            @JsonProperty("source") String source) {
        return builder()
                .documentId(documentId)
                .documentType(documentType)
                .documentNumber(documentNumber)
                .xmlContent(xmlContent)
                .correlationId(correlationId)
                .source(source)
                .build();
    }
}
