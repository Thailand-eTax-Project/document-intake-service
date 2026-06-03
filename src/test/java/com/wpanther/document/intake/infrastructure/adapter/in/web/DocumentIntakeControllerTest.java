package com.wpanther.document.intake.infrastructure.adapter.in.web;

import com.wpanther.document.intake.application.usecase.GetDocumentUseCase;
import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.exception.DuplicateDocumentException;
import com.wpanther.document.intake.domain.model.DocumentStatus;
import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.document.intake.domain.model.ValidationResult;
import com.wpanther.document.intake.infrastructure.config.validation.ValidationProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DocumentIntakeController.class,
    properties = "app.security.enabled=false",
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
    })
@DisplayName("Document Intake Controller Tests")
class DocumentIntakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubmitDocumentUseCase submitDocumentUseCase;

    @MockBean
    private GetDocumentUseCase getDocumentUseCase;

    @MockBean
    private ValidationProperties validationProperties;

    private IncomingDocument testDocument;

    @BeforeEach
    void setUp() {
        when(validationProperties.getMaxXmlSize()).thenReturn(10485760L);
        when(validationProperties.getMaxXmlSizeMb()).thenReturn(10);
        when(validationProperties.getMaxXmlDepth()).thenReturn(100);
        when(validationProperties.getMaxElementCount()).thenReturn(10000);

        testDocument = IncomingDocument.builder()
            .id(UUID.randomUUID())
            .documentNumber("INV-2024-001")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .correlationId("corr-123")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.VALIDATED)
            .validationResult(ValidationResult.success())
            .receivedAt(Instant.now())
            .build();
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 202 Accepted for valid document")
    void testSubmitInvoiceReturns202Accepted() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), eq("corr-123")))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-123"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value("corr-123"));
    }

    @Test
    @DisplayName("POST /api/v1/documents generates correlation ID when not provided")
    void testSubmitInvoiceGeneratesCorrelationId() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 400 for invalid XML")
    void testSubmitInvoiceReturns400ForInvalidXml() throws Exception {
        doThrow(new IllegalArgumentException("Could not extract document number"))
            .when(submitDocumentUseCase).submitDocument(any(), any(), any());

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("invalid xml"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid document"));
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 409 for duplicate document number")
    void testSubmitInvoiceReturns409ForDuplicateDocument() throws Exception {
        doThrow(new DuplicateDocumentException("INV-2024-001"))
            .when(submitDocumentUseCase).submitDocument(any(), any(), any());

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-dup"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Document already exists"));
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 413 when payload too large")
    void testSubmitInvoiceReturns413WhenPayloadTooLarge() throws Exception {
        when(validationProperties.getMaxXmlSize()).thenReturn(5L);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("Payload too large"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 200 OK when document exists")
    void testGetInvoiceByIdReturns200() throws Exception {
        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(testDocument);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentNumber").value("INV-2024-001"))
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.documentType").value("TAX_INVOICE"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 404 when document not found")
    void testGetInvoiceByIdReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(getDocumentUseCase.getDocument(unknownId))
            .thenThrow(new IllegalArgumentException("Document not found: " + unknownId));

        mockMvc.perform(get("/api/v1/documents/{id}", unknownId))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns validation result")
    void testGetInvoiceIncludesValidationResult() throws Exception {
        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(testDocument);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationResult.valid").value(true));
    }

    @Test
    @DisplayName("rateLimitFallback returns 429 Too Many Requests")
    void testRateLimitFallbackReturns429() {
        DocumentIntakeController controller = new DocumentIntakeController(
            submitDocumentUseCase, validationProperties, getDocumentUseCase);
        RequestNotPermitted ex = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("test"));

        ResponseEntity<Map<String, Object>> response =
            controller.rateLimitFallback("<xml/>", null, ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).isEqualTo("Rate limit exceeded");
    }

    @Test
    @DisplayName("POST /api/v1/documents returns 400 for blank body (@NotBlank violation)")
    void testSubmitInvoiceReturns400ForBlankBody() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/v1/documents accepts text/xml content type")
    void testSubmitInvoiceAcceptsTextXmlContentType() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.TEXT_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", "corr-text-xml"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value("corr-text-xml"));
    }

    @Test
    @DisplayName("POST /api/v1/documents with no X-Correlation-ID generates UUID")
    void testSubmitInvoiceWithNullCorrelationIdGeneratesUuid() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").isString())
            .andExpect(jsonPath("$.correlationId").value(
                org.hamcrest.Matchers.matchesPattern(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    @DisplayName("POST /api/v1/documents handles empty X-Correlation-ID header")
    void testSubmitInvoiceHandlesEmptyCorrelationId() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Correlation-ID", ""))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /api/v1/documents ignores X-Source header (source is always REST)")
    void testSubmitInvoiceIgnoresSourceHeader() throws Exception {
        when(submitDocumentUseCase.submitDocument(any(), eq("REST"), any()))
            .thenReturn(testDocument);

        mockMvc.perform(post("/api/v1/documents")
                .contentType(MediaType.APPLICATION_XML)
                .content("<test>xml</test>")
                .header("X-Source", "KAFKA"))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document with null documentType")
    void testGetInvoiceHandlesNullDocumentType() throws Exception {
        IncomingDocument documentWithNullType = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-003")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(null)
            .status(DocumentStatus.VALIDATING)
            .validationResult(ValidationResult.success())
            .receivedAt(java.time.Instant.now())
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithNullType);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VALIDATING"))
            .andExpect(jsonPath("$.documentType").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document without processedAt")
    void testGetInvoiceHandlesNullProcessedAt() throws Exception {
        IncomingDocument documentWithoutProcessedAt = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-004")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.RECEIVED)
            .validationResult(ValidationResult.success())
            .receivedAt(java.time.Instant.now())
            .processedAt(null)
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithoutProcessedAt);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.receivedAt").exists())
            .andExpect(jsonPath("$.processedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} handles document without validation result")
    void testGetInvoiceHandlesNullValidationResult() throws Exception {
        IncomingDocument documentWithoutValidation = IncomingDocument.builder()
            .id(testDocument.getId())
            .documentNumber("INV-2024-005")
            .xmlContent("<test>xml</test>")
            .source("REST")
            .documentType(DocumentType.TAX_INVOICE)
            .status(DocumentStatus.RECEIVED)
            .validationResult(null)
            .receivedAt(java.time.Instant.now())
            .build();

        when(getDocumentUseCase.getDocument(testDocument.getId()))
            .thenReturn(documentWithoutValidation);

        mockMvc.perform(get("/api/v1/documents/{id}", testDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationResult").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id} returns 500 for unexpected errors")
    void testGetInvoiceReturns500ForUnexpectedErrors() throws Exception {
        UUID testId = UUID.randomUUID();
        when(getDocumentUseCase.getDocument(testId))
            .thenThrow(new RuntimeException("Database connection failed"));

        mockMvc.perform(get("/api/v1/documents/{id}", testId))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Failed to retrieve document status"));
    }
}
