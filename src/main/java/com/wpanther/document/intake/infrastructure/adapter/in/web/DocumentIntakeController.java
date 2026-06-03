package com.wpanther.document.intake.infrastructure.adapter.in.web;

import com.wpanther.document.intake.application.usecase.GetDocumentUseCase;
import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.infrastructure.config.validation.ValidationProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
@Validated
@Tag(name = "Document Intake", description = "API for submitting and retrieving Thai e-Tax XML documents")
public class DocumentIntakeController {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeController.class);

    private final SubmitDocumentUseCase submitDocumentUseCase;
    private final ValidationProperties validationProperties;
    private final GetDocumentUseCase getDocumentUseCase;

    public DocumentIntakeController(
            SubmitDocumentUseCase submitDocumentUseCase,
            ValidationProperties validationProperties,
            GetDocumentUseCase getDocumentUseCase) {
        this.submitDocumentUseCase = submitDocumentUseCase;
        this.validationProperties = validationProperties;
        this.getDocumentUseCase = getDocumentUseCase;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @RateLimiter(name = "documentIntake", fallbackMethod = "rateLimitFallback")
    @Operation(
        summary = "Submit a Thai e-Tax XML document",
        description = "Submit an XML document for validation and processing. " +
                      "Valid documents trigger a saga orchestration workflow."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Document accepted for processing",
            content = @Content(schema = @Schema(implementation = SubmitDocumentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid document content",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Document number already exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Payload too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> submitDocument(
        @Parameter(description = "Thai e-Tax XML document content", required = true)
        @RequestBody @NotBlank String xmlContent,
        @Parameter(description = "Optional correlation ID for distributed tracing")
        @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        if (xmlContent.length() > validationProperties.getMaxXmlSize()) {
            log.warn("Document rejected - size exceeds maximum: {} > {}",
                xmlContent.length(), validationProperties.getMaxXmlSize());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "Payload too large",
                "message", "XML content exceeds maximum size of " +
                    validationProperties.getMaxXmlSizeMb() + "MB"
            ));
        }

        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        log.info("Received document submission via REST, correlationId: {}", effectiveCorrelationId);

        try {
            submitDocumentUseCase.submitDocument(xmlContent, "REST", effectiveCorrelationId);
            return ResponseEntity.accepted().body(Map.of(
                "message", "Document submitted for processing",
                "correlationId", effectiveCorrelationId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Document rejected — invalid content: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid document",
                "message", e.getMessage()
            ));
        } catch (DuplicateDocumentException e) {
            log.warn("Document rejected — duplicate: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Document already exists",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error submitting document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to submit document",
                "message", e.getMessage()
            ));
        }
    }

    public ResponseEntity<Map<String, Object>> rateLimitFallback(
            String xmlContent, String correlationId, RequestNotPermitted ex) {
        log.warn("Rate limit exceeded for document submission");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
            "error", "Rate limit exceeded",
            "message", "Too many requests. Please retry after a moment."
        ));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document status",
        description = "Retrieve the current status and details of a submitted document by its UUID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Document found",
            content = @Content(schema = @Schema(implementation = DocumentStatusResponse.class))),
        @ApiResponse(responseCode = "404", description = "Document not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> getDocumentStatus(
        @Parameter(description = "Document UUID")
        @PathVariable UUID id) {
        try {
            IncomingDocument document = getDocumentUseCase.getDocument(id);

            Map<String, Object> response = new HashMap<>();
            response.put("id", document.getId().toString());
            response.put("documentNumber", document.getDocumentNumber());
            response.put("status", document.getStatus().name());

            if (document.getDocumentType() != null) {
                response.put("documentType", document.getDocumentType().name());
            }
            if (document.getReceivedAt() != null) {
                response.put("receivedAt", document.getReceivedAt().toString());
            }
            if (document.getProcessedAt() != null) {
                response.put("processedAt", document.getProcessedAt().toString());
            }
            if (document.getValidationResult() != null) {
                response.put("validationResult", Map.of(
                    "valid", document.getValidationResult().valid(),
                    "errors", document.getValidationResult().errors(),
                    "warnings", document.getValidationResult().warnings()
                ));
            }
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error retrieving document status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve document status"
            ));
        }
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .collect(Collectors.joining("; "));
        log.warn("Request constraint violation: {}", message);
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid request",
            "message", message
        ));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getAllErrors().stream()
            .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Validation failed")
            .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", message);
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid request",
            "message", message
        ));
    }

    @Schema(description = "Response returned when document is accepted")
    private static class SubmitDocumentResponse {
        @Schema(example = "Document submitted for processing") private String message;
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000") private String correlationId;
    }

    @Schema(description = "Document status and details")
    private static class DocumentStatusResponse {
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000") private String id;
        @Schema(example = "TAX-2025-001") private String documentNumber;
        @Schema(example = "VALIDATED") private String status;
        @Schema(example = "TAX_INVOICE") private String documentType;
        @Schema(example = "2025-01-15T10:30:00Z") private String receivedAt;
        @Schema(example = "2025-01-15T10:30:05Z") private String processedAt;
        @Schema private ValidationResultSchema validationResult;
    }

    @Schema(description = "Validation result details")
    private static class ValidationResultSchema {
        @Schema(example = "true") private boolean valid;
        @Schema(example = "[]") private java.util.List<String> errors;
        @Schema(example = "[]") private java.util.List<String> warnings;
    }

    @Schema(description = "Error response")
    private static class ErrorResponse {
        @Schema(example = "Invalid document") private String error;
        @Schema(example = "Document type could not be determined") private String message;
    }
}
