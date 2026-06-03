package com.wpanther.document.intake.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.exception.ValidationResultSerializationException;
import com.wpanther.document.intake.domain.model.DocumentStatus;
import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.domain.model.ValidationResult;
import com.wpanther.document.intake.domain.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository adapter that bridges between domain IncomingDocument aggregate
 * and JPA IncomingDocumentEntity.
 */
@Component
public class JpaDocumentRepository implements DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaDocumentRepository.class);

    private final JpaIncomingDocumentRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public JpaDocumentRepository(JpaIncomingDocumentRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

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

    @Override
    public Optional<IncomingDocument> findById(UUID id) {
        return jpaRepository.findById(id)
            .map(this::toDomain);
    }

    @Override
    public Optional<IncomingDocument> findByDocumentNumber(String documentNumber) {
        return jpaRepository.findByDocumentNumber(documentNumber)
            .map(this::toDomain);
    }

    @Override
    public List<IncomingDocument> findByStatus(DocumentStatus status) {
        return jpaRepository.findByStatus(status).stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public boolean existsByDocumentNumber(String documentNumber) {
        return jpaRepository.existsByDocumentNumber(documentNumber);
    }

    /**
     * Convert domain model to JPA entity
     */
    private IncomingDocumentEntity toEntity(IncomingDocument document) {
        ValidationResult validationResult = document.getValidationResult();

        return IncomingDocumentEntity.builder()
            .id(document.getId())
            .documentNumber(document.getDocumentNumber())
            .xmlContent(document.getXmlContent())
            .source(document.getSource())
            .correlationId(document.getCorrelationId())
            .documentType(document.getDocumentType())
            .status(document.getStatus())
            .validationResult(validationResult != null ? mapValidationResult(validationResult) : null)
            .receivedAt(document.getReceivedAt())
            .processedAt(document.getProcessedAt())
            .build();
    }

    /**
     * Convert JPA entity to domain model
     */
    private IncomingDocument toDomain(IncomingDocumentEntity entity) {
        IncomingDocument.Builder builder = IncomingDocument.builder()
            .id(entity.getId())
            .documentNumber(entity.getDocumentNumber())
            .xmlContent(entity.getXmlContent())
            .source(entity.getSource())
            .correlationId(entity.getCorrelationId())
            .documentType(entity.getDocumentType())
            .status(entity.getStatus())
            .receivedAt(entity.getReceivedAt())
            .processedAt(entity.getProcessedAt());

        // Map validation result if present
        if (entity.getValidationResult() != null) {
            builder.validationResult(mapValidationResultFromEntity(entity.getValidationResult()));
        }

        return builder.build();
    }

    /**
     * Convert ValidationResult to JSON string for storage
     */
    private String mapValidationResult(ValidationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Failed to serialize ValidationResult to JSON", e);
            throw new ValidationResultSerializationException(
                "Failed to serialize ValidationResult to JSON. " +
                "This may indicate a problem with the ValidationResult structure. " +
                "Error: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Convert JSON string from storage to ValidationResult
     */
    private ValidationResult mapValidationResultFromEntity(String json) {
        try {
            return objectMapper.readValue(json, ValidationResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ValidationResult from JSON: {}", json, e);
            throw new ValidationResultSerializationException(
                "Failed to deserialize ValidationResult from JSON. " +
                "This may indicate corrupted data in the database or schema mismatch. " +
                "JSON: " + (json.length() > 100 ? json.substring(0, 100) + "..." : json) + ". " +
                "Error: " + e.getMessage(),
                e
            );
        }
    }
}
