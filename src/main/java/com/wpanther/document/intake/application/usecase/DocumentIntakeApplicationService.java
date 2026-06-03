package com.wpanther.document.intake.application.usecase;

import com.wpanther.document.intake.application.port.out.DocumentIntakeMetricsPort;
import com.wpanther.document.intake.application.dto.event.DocumentReceivedTraceEvent;
import com.wpanther.document.intake.application.dto.event.StartSagaCommand;
import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.domain.model.ValidationResult;
import com.wpanther.document.intake.application.port.out.DocumentEventPublisher;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.repository.DocumentRepository;
import com.wpanther.document.intake.application.port.out.XmlValidationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for document intake operations.
 * <p>
 * This service handles document submission, validation, and event publishing via the outbox pattern.
 * Events are written to the outbox table within the same transaction as domain state changes,
 * ensuring atomicity and guaranteed delivery.
 * <p>
 * Metrics are recorded for document intake rates, validation results, and processing times.
 */
@Service
public class DocumentIntakeApplicationService implements SubmitDocumentUseCase, GetDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeApplicationService.class);

    private final DocumentRepository documentRepository;
    private final XmlValidationPort validationService;
    private final DocumentEventPublisher eventPublisher;
    private final DocumentIntakeMetricsPort metrics;

    public DocumentIntakeApplicationService(DocumentRepository documentRepository,
                                XmlValidationPort validationService,
                                DocumentEventPublisher eventPublisher,
                                DocumentIntakeMetricsPort metrics) {
        this.documentRepository = documentRepository;
        this.validationService = validationService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    /**
     * Submit and validate document.
     * <p>
     * This method:
     * 1. Validates the XML content
     * 2. Creates and saves an IncomingDocument to the database
     * 3. Publishes a DocumentReceivedTraceEvent (for notification-service)
     * 4. Performs document validation
     * 5. If validated successfully, publishes a StartSagaCommand (for orchestrator-service)
     * <p>
     * All database changes and event writes happen within a single transaction.
     * Events are written to the outbox table and Debezium CDC publishes them to Kafka.
     * <p>
     * Metrics are recorded for monitoring and observability.
     *
     * @param xmlContent the XML content to validate
     * @param source the source of the document (API, KAFKA, etc.)
     * @param correlationId the correlation ID for tracing
     * @return the created IncomingDocument
     * @throws IllegalArgumentException if document number or document type cannot be extracted
     * @throws IllegalStateException if document number already exists
     */
    @Transactional
    @Override
    public IncomingDocument submitDocument(String xmlContent, String source, String correlationId) {
        // Normalize XML: remove inter-element whitespace using XML-aware DOM transformation
        xmlContent = validationService.normalize(xmlContent);
        long startTime = System.currentTimeMillis();
        log.info("Submitting document from source: {} with correlationId: {}", source, correlationId);

        // Record document received
        metrics.incrementReceived();

        // Extract document number
        String documentNumber = validationService.extractDocumentNumber(xmlContent);
        if (documentNumber == null || documentNumber.isBlank()) {
            metrics.incrementInvalid("missing_document_number");
            throw new IllegalArgumentException(
                "Could not extract document number from XML. " +
                "Ensure XML has valid document number in the expected field location. " +
                "Document type may not be recognized or document number field may be missing."
            );
        }

        // Extract document type
        DocumentType documentType = validationService.extractDocumentType(xmlContent);
        if (documentType == null) {
            metrics.incrementInvalid("unknown_document_type");
            throw new IllegalArgumentException(
                "Could not detect document type from XML. " +
                "Ensure XML namespace URI matches one of the supported document types: " +
                "TAX_INVOICE, RECEIPT, INVOICE, DEBIT_CREDIT_NOTE, CANCELLATION_NOTE, ABBREVIATED_TAX_INVOICE."
            );
        }
        log.debug("Detected document type: {}", documentType);

        // Check if already exists
        if (documentRepository.existsByDocumentNumber(documentNumber)) {
            log.warn("Document number {} already exists", documentNumber);
            metrics.incrementFailed("duplicate_document_number");
            throw new DuplicateDocumentException(documentNumber);
        }

        // Create incoming document
        IncomingDocument document = IncomingDocument.builder()
            .documentNumber(documentNumber)
            .xmlContent(xmlContent)
            .source(source)
            .correlationId(correlationId)
            .documentType(documentType)
            .build();

        // Save initial state
        document = documentRepository.save(document);
        log.info("Created incoming document: {} with ID: {}", documentNumber, document.getId());

        // Publish trace event IMMEDIATELY (before validation)
        // This provides visibility into the document intake process
        DocumentReceivedTraceEvent traceEvent = DocumentReceivedTraceEvent.builder()
            .documentId(document.getId().toString())
            .documentType(document.getDocumentType().name())
            .documentNumber(document.getDocumentNumber())
            .correlationId(correlationId)
            .status(document.getStatus().name())
            .source(source)
            .build();
        eventPublisher.publishTraceEvent(traceEvent);

        // Start validation
        document.startValidation();
        document = documentRepository.save(document);

        // Perform validation
        ValidationResult validationResult = validationService.validate(xmlContent);

        // Mark validation result
        document.markValidated(validationResult);
        document = documentRepository.save(document);

        log.info("Document {} validation result: valid={}, errors={}, warnings={}",
            documentNumber, validationResult.valid(), validationResult.errorCount(), validationResult.warningCount());

        // Publish StartSagaCommand AFTER validation (only for valid documents)
        // This triggers the saga orchestrator to begin the multi-step processing pipeline
        if (document.isValid()) {
            // Record validation success
            metrics.incrementValidated(documentType.name());

            StartSagaCommand sagaCommand = StartSagaCommand.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType().name())
                .documentNumber(document.getDocumentNumber())
                .xmlContent(xmlContent)
                .correlationId(correlationId)
                .source(source)
                .build();
            eventPublisher.publishStartSagaCommand(sagaCommand);

            // Publish VALIDATED trace event
            DocumentReceivedTraceEvent validatedEvent = DocumentReceivedTraceEvent.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType().name())
                .documentNumber(document.getDocumentNumber())
                .correlationId(correlationId)
                .status(document.getStatus().name())
                .source(source)
                .build();
            eventPublisher.publishTraceEvent(validatedEvent);

            // Mark document as FORWARDED after saga command is published
            document.markForwarded();
            document = documentRepository.save(document);

            // Record forward to orchestrator
            metrics.incrementForwarded(documentType.name());

            // Publish FORWARDED trace event
            DocumentReceivedTraceEvent forwardedEvent = DocumentReceivedTraceEvent.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType().name())
                .documentNumber(document.getDocumentNumber())
                .correlationId(correlationId)
                .status(document.getStatus().name())
                .source(source)
                .build();
            eventPublisher.publishTraceEvent(forwardedEvent);
        } else {
            // Record validation failure
            String failureReason = validationResult.errorCount() > 0
                ? "validation_errors"
                : "validation_warnings";
            metrics.incrementInvalid(failureReason);

            // Publish INVALID trace event so notification-service tracks rejected documents
            DocumentReceivedTraceEvent invalidEvent = DocumentReceivedTraceEvent.builder()
                .documentId(document.getId().toString())
                .documentType(document.getDocumentType().name())
                .documentNumber(document.getDocumentNumber())
                .correlationId(correlationId)
                .status(document.getStatus().name())
                .source(source)
                .build();
            eventPublisher.publishTraceEvent(invalidEvent);

            log.warn("Document {} failed validation with {} error(s)",
                documentNumber, document.getValidationResult().errorCount());
        }

        // Record processing time
        long processingTime = System.currentTimeMillis() - startTime;
        metrics.recordProcessingTime(processingTime);
        log.debug("Document processing time: {} ms", processingTime);

        return document;
    }

    /**
     * Get document by ID.
     *
     * @param id the document ID
     * @return the document
     * @throws IllegalArgumentException if document not found
     */
    @Transactional(readOnly = true)
    @Override
    public IncomingDocument getDocument(UUID id) {
        return documentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
    }
}
