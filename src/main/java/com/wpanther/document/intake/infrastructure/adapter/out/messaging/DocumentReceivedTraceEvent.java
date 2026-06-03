package com.wpanther.document.intake.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a document is received by document-intake-service.
 * Published to topic: trace.document.received
 * <p>
 * Infrastructure-layer message object — carries Jackson serialization annotations
 * required for the outbox → Kafka wire format. Constructed by {@link EventPublisher}
 * from domain types; the application layer has no direct dependency on this class.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentReceivedTraceEvent extends TraceEvent {

    private static final long serialVersionUID = 1L;

    private final String documentId;
    private final String documentType;
    private final String documentNumber;
    private final String status;

    private DocumentReceivedTraceEvent(
            String documentId,
            String documentType,
            String documentNumber,
            String correlationId,
            String status,
            String source) {
        super(documentId, correlationId, source, status, null);
        this.documentId = documentId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.status = status;
    }

    @JsonProperty("documentId")
    public String getDocumentId() { return documentId; }

    @JsonProperty("documentType")
    public String getDocumentType() { return documentType; }

    @JsonProperty("documentNumber")
    public String getDocumentNumber() { return documentNumber; }

    @JsonProperty("status")
    public String getStatus() { return status; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String documentId;
        private String documentType;
        private String documentNumber;
        private String correlationId;
        private String status;
        private String source;

        public Builder documentId(String v)     { this.documentId = v;     return this; }
        public Builder documentType(String v)   { this.documentType = v;   return this; }
        public Builder documentNumber(String v) { this.documentNumber = v; return this; }
        public Builder correlationId(String v)  { this.correlationId = v;  return this; }
        public Builder status(String v)         { this.status = v;         return this; }
        public Builder source(String v)         { this.source = v;         return this; }

        public DocumentReceivedTraceEvent build() {
            return new DocumentReceivedTraceEvent(documentId, documentType, documentNumber,
                                                  correlationId, status, source);
        }
    }

    @JsonCreator
    static DocumentReceivedTraceEvent fromJson(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("traceType") String traceType,
            @JsonProperty("context") String context,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("documentType") String documentType,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("status") String status,
            @JsonProperty("source") String source) {
        return builder()
                .documentId(documentId)
                .documentType(documentType)
                .documentNumber(documentNumber)
                .correlationId(correlationId)
                .status(status)
                .source(source)
                .build();
    }
}
