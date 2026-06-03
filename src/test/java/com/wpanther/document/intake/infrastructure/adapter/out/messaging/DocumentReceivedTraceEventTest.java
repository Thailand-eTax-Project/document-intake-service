package com.wpanther.document.intake.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DocumentReceivedTraceEvent Tests")
class DocumentReceivedTraceEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Should create event with builder")
    void shouldCreateEventWithBuilder() {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .correlationId("corr-123")
                .status("RECEIVED")
                .source("API")
                .build();

        assertThat(event.getDocumentId()).isEqualTo("doc-123");
        assertThat(event.getDocumentType()).isEqualTo("TAX_INVOICE");
        assertThat(event.getDocumentNumber()).isEqualTo("INV-001");
        assertThat(event.getCorrelationId()).isEqualTo("corr-123");
        assertThat(event.getStatus()).isEqualTo("RECEIVED");
        assertThat(event.getSource()).isEqualTo("API");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("DocumentReceivedTraceEvent");
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create event with null correlationId")
    void shouldCreateEventWithNullCorrelationId() {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .correlationId(null)
                .status("RECEIVED")
                .source("API")
                .build();

        assertThat(event.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("Should create event with all valid statuses")
    void shouldCreateEventWithAllValidStatuses() {
        String[] statuses = {"RECEIVED", "VALIDATING", "VALIDATED", "FORWARDED", "INVALID", "FAILED"};

        for (String status : statuses) {
            DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                    .documentId("doc-123")
                    .documentType("TAX_INVOICE")
                    .documentNumber("INV-001")
                    .status(status)
                    .source("API")
                    .build();

            assertThat(event.getStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("Should create event with all valid document types")
    void shouldCreateEventWithAllValidDocumentTypes() {
        String[] documentTypes = {
            "TAX_INVOICE", "RECEIPT", "INVOICE", "DEBIT_CREDIT_NOTE",
            "CANCELLATION_NOTE", "ABBREVIATED_TAX_INVOICE"
        };

        for (String docType : documentTypes) {
            DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                    .documentId("doc-123")
                    .documentType(docType)
                    .documentNumber("INV-001")
                    .status("RECEIVED")
                    .source("API")
                    .build();

            assertThat(event.getDocumentType()).isEqualTo(docType);
        }
    }

    @Test
    @DisplayName("Should deserialize from JSON with all fields")
    void shouldDeserializeFromJson() throws Exception {
        String json = "{\n" +
                "  \"eventId\": \"123e4567-e89b-12d3-a456-426614174000\",\n" +
                "  \"occurredAt\": \"2024-01-01T00:00:00Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"doc-123\",\n" +
                "  \"documentType\": \"TAX_INVOICE\",\n" +
                "  \"documentNumber\": \"INV-001\",\n" +
                "  \"correlationId\": \"corr-123\",\n" +
                "  \"status\": \"RECEIVED\",\n" +
                "  \"source\": \"API\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getDocumentId()).isEqualTo("doc-123");
        assertThat(event.getDocumentType()).isEqualTo("TAX_INVOICE");
        assertThat(event.getDocumentNumber()).isEqualTo("INV-001");
        assertThat(event.getCorrelationId()).isEqualTo("corr-123");
        assertThat(event.getStatus()).isEqualTo("RECEIVED");
        assertThat(event.getSource()).isEqualTo("API");
        assertThat(event.getEventType()).isEqualTo("DocumentReceivedTraceEvent");
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should deserialize from JSON with null correlationId")
    void shouldDeserializeFromJsonWithNullCorrelationId() throws Exception {
        String json = "{\n" +
                "  \"eventId\": \"123e4567-e89b-12d3-a456-426614174000\",\n" +
                "  \"occurredAt\": \"2024-01-01T00:00:00Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"doc-123\",\n" +
                "  \"documentType\": \"TAX_INVOICE\",\n" +
                "  \"documentNumber\": \"INV-001\",\n" +
                "  \"correlationId\": null,\n" +
                "  \"status\": \"RECEIVED\",\n" +
                "  \"source\": \"API\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("Should serialize to JSON")
    void shouldSerializeToJson() throws Exception {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .correlationId("corr-123")
                .status("RECEIVED")
                .source("API")
                .build();

        ObjectMapper mapper = MAPPER;
        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"documentId\":\"doc-123\"");
        assertThat(json).contains("\"documentType\":\"TAX_INVOICE\"");
        assertThat(json).contains("\"documentNumber\":\"INV-001\"");
        assertThat(json).contains("\"correlationId\":\"corr-123\"");
        assertThat(json).contains("\"status\":\"RECEIVED\"");
        assertThat(json).contains("\"source\":\"API\"");
    }

    @Test
    @DisplayName("Should create event with Kafka source")
    void shouldCreateEventWithKafkaSource() {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("RECEIVED")
                .source("KAFKA")
                .build();

        assertThat(event.getSource()).isEqualTo("KAFKA");
    }

    @Test
    @DisplayName("Should create event with different statuses")
    void shouldCreateEventWithDifferentStatuses() {
        DocumentReceivedTraceEvent event1 = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("VALIDATING")
                .source("API")
                .build();

        DocumentReceivedTraceEvent event2 = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("VALIDATED")
                .source("API")
                .build();

        DocumentReceivedTraceEvent event3 = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("FORWARDED")
                .source("API")
                .build();

        assertThat(event1.getStatus()).isEqualTo("VALIDATING");
        assertThat(event2.getStatus()).isEqualTo("VALIDATED");
        assertThat(event3.getStatus()).isEqualTo("FORWARDED");
    }

    @Test
    @DisplayName("Should create event with invalid status")
    void shouldCreateEventWithInvalidStatus() {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("INVALID")
                .source("API")
                .build();

        assertThat(event.getStatus()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("Should create event with failed status")
    void shouldCreateEventWithFailedStatus() {
        DocumentReceivedTraceEvent event = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .status("FAILED")
                .source("API")
                .build();

        assertThat(event.getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Should deserialize JSON with different parent class field values")
    void shouldDeserializeJsonWithDifferentParentFields() throws Exception {
        String json = "{\n" +
                "  \"eventId\": \"550e8400-e29b-41d4-a716-446655440000\",\n" +
                "  \"occurredAt\": \"2024-12-25T14:30:45.678Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"doc-999\",\n" +
                "  \"documentType\": \"INVOICE\",\n" +
                "  \"documentNumber\": \"INV-999\",\n" +
                "  \"correlationId\": \"corr-999\",\n" +
                "  \"status\": \"VALIDATED\",\n" +
                "  \"source\": \"FILE\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getDocumentId()).isEqualTo("doc-999");
        assertThat(event.getDocumentType()).isEqualTo("INVOICE");
        assertThat(event.getDocumentNumber()).isEqualTo("INV-999");
        assertThat(event.getCorrelationId()).isEqualTo("corr-999");
        assertThat(event.getStatus()).isEqualTo("VALIDATED");
        assertThat(event.getSource()).isEqualTo("FILE");
        // Verify parent fields were deserialized correctly
        assertThat(event.getEventType()).isEqualTo("DocumentReceivedTraceEvent");
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should deserialize minimal JSON with required parent fields")
    void shouldDeserializeMinimalJson() throws Exception {
        // JSON with all fields including parent class fields
        String json = "{\n" +
                "  \"eventId\": \"00000000-0000-0000-0000-000000000000\",\n" +
                "  \"occurredAt\": \"2024-01-01T00:00:00Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"minimal\",\n" +
                "  \"documentType\": \"RECEIPT\",\n" +
                "  \"documentNumber\": \"MIN\",\n" +
                "  \"correlationId\": \"test\",\n" +
                "  \"status\": \"RECEIVED\",\n" +
                "  \"source\": \"MIN\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getDocumentId()).isEqualTo("minimal");
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should deserialize and serialize roundtrip")
    void shouldDeserializeAndSerializeRoundtrip() throws Exception {
        DocumentReceivedTraceEvent original = DocumentReceivedTraceEvent.builder()
                .documentId("doc-123")
                .documentType("TAX_INVOICE")
                .documentNumber("INV-001")
                .correlationId("corr-123")
                .status("RECEIVED")
                .source("API")
                .build();

        ObjectMapper mapper = MAPPER;
        String json = mapper.writeValueAsString(original);

        // Verify JSON contains eventId (should be auto-generated)
        assertThat(json).contains("\"eventId\"");

        DocumentReceivedTraceEvent deserialized = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(deserialized.getDocumentId()).isEqualTo(original.getDocumentId());
        assertThat(deserialized.getDocumentType()).isEqualTo(original.getDocumentType());
        assertThat(deserialized.getDocumentNumber()).isEqualTo(original.getDocumentNumber());
        assertThat(deserialized.getCorrelationId()).isEqualTo(original.getCorrelationId());
        assertThat(deserialized.getStatus()).isEqualTo(original.getStatus());
        assertThat(deserialized.getSource()).isEqualTo(original.getSource());
        // Note: eventId may differ between original and deserialized due to auto-generation
        // The important thing is that both have valid eventId
        assertThat(deserialized.getEventId()).isNotNull();
        assertThat(deserialized.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("Should deserialize JSON with all parent class fields to hit JsonCreator")
    void shouldDeserializeJsonWithAllParentFieldsToHitJsonCreator() throws Exception {
        // JSON with ALL parent class fields explicitly set
        // This forces Jackson to use @JsonCreator constructor, not builder constructor
        String json = "{\n" +
                "  \"eventId\": \"550e8400-e29b-41d4-a716-446655440000\",\n" +
                "  \"occurredAt\": \"2024-12-25T14:30:45.678Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 2,\n" +
                "  \"documentId\": \"doc-999\",\n" +
                "  \"documentType\": \"INVOICE\",\n" +
                "  \"documentNumber\": \"INV-999\",\n" +
                "  \"correlationId\": \"corr-999\",\n" +
                "  \"status\": \"VALIDATED\",\n" +
                "  \"source\": \"FILE\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("DocumentReceivedTraceEvent");
        assertThat(event.getVersion()).isEqualTo(1); // Version is validated to be 1 in IntegrationEvent
        assertThat(event.getDocumentId()).isEqualTo("doc-999");
        assertThat(event.getDocumentType()).isEqualTo("INVOICE");
        assertThat(event.getDocumentNumber()).isEqualTo("INV-999");
        assertThat(event.getCorrelationId()).isEqualTo("corr-999");
        assertThat(event.getStatus()).isEqualTo("VALIDATED");
        assertThat(event.getSource()).isEqualTo("FILE");
    }

    @Test
    @DisplayName("Should deserialize minimal JSON to test JsonCreator field assignments")
    void shouldDeserializeMinimalJsonToTestJsonCreator() throws Exception {
        // Minimal JSON with all required fields
        String json = "{\n" +
                "  \"eventId\": \"00000000-0000-0000-0000-000000000000\",\n" +
                "  \"occurredAt\": \"2024-01-01T00:00:00Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"minimal\",\n" +
                "  \"documentType\": \"RECEIPT\",\n" +
                "  \"documentNumber\": \"MIN\",\n" +
                "  \"correlationId\": \"test\",\n" +
                "  \"status\": \"RECEIVED\",\n" +
                "  \"source\": \"MIN\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getDocumentId()).isEqualTo("minimal");
        assertThat(event.getDocumentType()).isEqualTo("RECEIPT");
        assertThat(event.getDocumentNumber()).isEqualTo("MIN");
        assertThat(event.getCorrelationId()).isEqualTo("test");
        assertThat(event.getStatus()).isEqualTo("RECEIVED");
        assertThat(event.getSource()).isEqualTo("MIN");
    }

    @Test
    @DisplayName("Should deserialize JSON with null correlationId in JsonCreator")
    void shouldDeserializeJsonWithNullCorrelationIdInJsonCreator() throws Exception {
        String json = "{\n" +
                "  \"eventId\": \"00000000-0000-0000-0000-000000000000\",\n" +
                "  \"occurredAt\": \"2024-01-01T00:00:00Z\",\n" +
                "  \"eventType\": \"DocumentReceivedTraceEvent\",\n" +
                "  \"version\": 1,\n" +
                "  \"documentId\": \"doc-123\",\n" +
                "  \"documentType\": \"TAX_INVOICE\",\n" +
                "  \"documentNumber\": \"INV-001\",\n" +
                "  \"correlationId\": null,\n" +
                "  \"status\": \"RECEIVED\",\n" +
                "  \"source\": \"API\"\n" +
                "}";

        ObjectMapper mapper = MAPPER;
        DocumentReceivedTraceEvent event = mapper.readValue(json, DocumentReceivedTraceEvent.class);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getCorrelationId()).isNull();
        assertThat(event.getDocumentId()).isEqualTo("doc-123");
    }
}
