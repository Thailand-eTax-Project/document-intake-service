package com.wpanther.document.intake.application.dto.event;

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
 * This command contains all information orchestrator needs to begin
 * orchestrating multi-step document processing pipeline.
 * <p>
 * Extends {@link SagaCommand} (non-sealed) as required by the sealed
 * {@code IntegrationEvent} hierarchy. {@code sagaId} and {@code sagaStep}
 * are {@code null} on creation because the saga has not started yet — the
 * orchestrator-service assigns them when it creates the saga instance.
 * <p>
 * <strong>Domain purity:</strong> This class uses a manual builder pattern
 * instead of Lombok to maintain zero compile-time dependencies in the domain layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StartSagaCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;
    private static final String DOCUMENT_TYPE_PATTERN =
        "TAX_INVOICE|RECEIPT|INVOICE|DEBIT_CREDIT_NOTE|CANCELLATION_NOTE|ABBREVIATED_TAX_INVOICE";

    private final String documentId;
    private final String documentType;
    private final String documentNumber;
    private final String xmlContent;
    private final String source;

    /**
     * Private constructor used by the builder.
     * sagaId and sagaStep are {@code null} because the saga has not started yet.
     */
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

    // ==================== Getters ====================

    /**
     * ID of IncomingDocument that triggered this saga.
     */
    @JsonProperty("documentId")
    public String getDocumentId() {
        return documentId;
    }

    /**
     * Type of document (TAX_INVOICE, INVOICE, RECEIPT, etc.)
     */
    @JsonProperty("documentType")
    public String getDocumentType() {
        return documentType;
    }

    /**
     * The document number from document.
     */
    @JsonProperty("documentNumber")
    public String getDocumentNumber() {
        return documentNumber;
    }

    /**
     * The full XML content of document.
     * This will be passed through pipeline for processing and signing.
     */
    @JsonProperty("xmlContent")
    public String getXmlContent() {
        return xmlContent;
    }

    /**
     * Source of document (API, KAFKA, etc.)
     */
    @JsonProperty("source")
    public String getSource() {
        return source;
    }

    // ==================== Builder ====================

    /**
     * Builder for {@link StartSagaCommand}.
     * <p>
     * Provides a fluent API for creating immutable StartSagaCommand instances.
     */
    public static class Builder {
        private String documentId;
        private String documentType;
        private String documentNumber;
        private String xmlContent;
        private String correlationId;
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

        public Builder xmlContent(String xmlContent) {
            this.xmlContent = xmlContent;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Builds the StartSagaCommand instance.
         *
         * @return a new {@link StartSagaCommand} with the values set in this builder
         * @throws IllegalArgumentException if required fields are null or blank
         */
        public StartSagaCommand build() {
            return new StartSagaCommand(
                documentId,
                documentType,
                documentNumber,
                xmlContent,
                correlationId,
                source
            );
        }
    }

    /**
     * Creates a new builder for {@link StartSagaCommand}.
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
     * the original {@code @Jacksonized} behaviour. Saga-infrastructure fields
     * ({@code sagaId}, {@code sagaStep}) present in JSON are intentionally ignored
     * since they are assigned by the orchestrator, not by this service.
     */
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
        return StartSagaCommand.builder()
                .documentId(documentId)
                .documentType(documentType)
                .documentNumber(documentNumber)
                .xmlContent(xmlContent)
                .correlationId(correlationId)
                .source(source)
                .build();
    }
}
