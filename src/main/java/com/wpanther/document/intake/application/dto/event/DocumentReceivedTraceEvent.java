package com.wpanther.document.intake.application.dto.event;

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
 * This event marks the initial receipt of a document and is used for
 * distributed tracing across microservices.
 * <p>
 * Extends {@link TraceEvent} (non-sealed) as required by the sealed
 * {@code IntegrationEvent} hierarchy. Field mappings to {@code TraceEvent}:
 * {@code sagaId} ← {@code documentId} (before a saga exists, the document ID
 * serves as the correlation handle); {@code traceType} ← {@code status}
 * (the document lifecycle status IS the type of trace event).
 * {@code source} is inherited from {@code TraceEvent} — not redeclared here.
 * <p>
 * <strong>Domain purity:</strong> This class uses a manual builder pattern
 * instead of Lombok to maintain zero compile-time dependencies in the domain layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentReceivedTraceEvent extends TraceEvent {

    private static final long serialVersionUID = 1L;

    private final String documentId;
    private final String documentType;
    private final String documentNumber;
    private final String status;

    /**
     * Private constructor used by the builder.
     * Maps: documentId → sagaId, status → traceType, source → TraceEvent.source.
     */
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

    // ==================== Getters ====================

    /**
     * ID of IncomingDocument that was received.
     */
    @JsonProperty("documentId")
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Type of document (TAX_INVOICE, RECEIPT, INVOICE, etc.)
     */
    @JsonProperty("documentType")
    public String getDocumentType() {
        return documentType;
    }

    /**
     * The document number from the document.
     */
    @JsonProperty("documentNumber")
    public String getDocumentNumber() {
        return documentNumber;
    }

    /**
     * Current status of document (RECEIVED, VALIDATED, etc.)
     */
    @JsonProperty("status")
    public String getStatus() {
        return status;
    }

    // ==================== Builder ====================

    /**
     * Builder for {@link DocumentReceivedTraceEvent}.
     * <p>
     * Provides a fluent API for creating immutable DocumentReceivedTraceEvent instances.
     */
    public static class Builder {
        private String documentId;
        private String documentType;
        private String documentNumber;
        private String correlationId;
        private String status;
        private String source;

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder documentType(String documentType) {
            this.documentType = documentType;
            return this;
        }

        public Builder documentNumber(String documentNumber) {
            this.documentNumber = documentNumber;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Builds the DocumentReceivedTraceEvent instance.
         *
         * @return a new {@link DocumentReceivedTraceEvent} with the values set in this builder
         * @throws IllegalArgumentException if required fields are null or blank
         */
        public DocumentReceivedTraceEvent build() {
            return new DocumentReceivedTraceEvent(
                documentId,
                documentType,
                documentNumber,
                correlationId,
                status,
                source
            );
        }
    }

    /**
     * Creates a new builder for {@link DocumentReceivedTraceEvent}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Jackson @JsonCreator ====================

    /**
     * Jackson deserialization factory. Always delegates to the builder (creation path)
     * so that {@code eventId} and {@code version} are always auto-generated, matching
     * the original {@code @Jacksonized} behaviour.
     */
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
        return DocumentReceivedTraceEvent.builder()
                .documentId(documentId)
                .documentType(documentType)
                .documentNumber(documentNumber)
                .correlationId(correlationId)
                .status(status)
                .source(source)
                .build();
    }
}
