package com.wpanther.document.intake.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.document.intake.domain.model.DocumentStatus;
import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublisher Tests")
class EventPublisherTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private ObjectMapper objectMapper;

    private EventPublisher eventPublisher;

    private IncomingDocument testDocument;

    @BeforeEach
    void setUp() {
        eventPublisher = new EventPublisher(outboxService, objectMapper);

        testDocument = IncomingDocument.builder()
                .documentNumber("INV-001")
                .xmlContent("<xml></xml>")
                .source("API")
                .correlationId("corr-123")
                .documentType(DocumentType.TAX_INVOICE)
                .status(DocumentStatus.VALIDATED)
                .build();
    }

    @Test
    @DisplayName("publishStartSagaCommand routes to saga.commands.orchestrator with correct headers")
    void shouldPublishStartSagaCommand() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"correlationId\":\"corr-123\",\"documentType\":\"TAX_INVOICE\"}");

        eventPublisher.publishStartSagaCommand(testDocument, "<xml></xml>");

        ArgumentCaptor<String> headersJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<StartSagaCommand> commandCaptor = ArgumentCaptor.forClass(StartSagaCommand.class);

        verify(objectMapper).writeValueAsString(anyMap());
        verify(outboxService).saveWithRouting(
                commandCaptor.capture(),
                eq("IncomingDocument"),
                eq(testDocument.getId().toString()),
                eq("saga.commands.orchestrator"),
                eq("corr-123"),
                headersJsonCaptor.capture()
        );

        StartSagaCommand captured = commandCaptor.getValue();
        assertThat(captured.getDocumentId()).isEqualTo(testDocument.getId().toString());
        assertThat(captured.getDocumentType()).isEqualTo("TAX_INVOICE");
        assertThat(captured.getDocumentNumber()).isEqualTo("INV-001");
        assertThat(captured.getXmlContent()).isEqualTo("<xml></xml>");
        assertThat(captured.getCorrelationId()).isEqualTo("corr-123");
        assertThat(captured.getSource()).isEqualTo("API");
        assertThat(headersJsonCaptor.getValue()).contains("corr-123").contains("TAX_INVOICE");
    }

    @Test
    @DisplayName("publishTraceEvent routes to trace.document.received with document status")
    void shouldPublishDocumentReceivedTraceEvent() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"correlationId\":\"corr-123\",\"documentType\":\"TAX_INVOICE\"}");

        eventPublisher.publishTraceEvent(testDocument);

        ArgumentCaptor<String> headersJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DocumentReceivedTraceEvent> eventCaptor =
                ArgumentCaptor.forClass(DocumentReceivedTraceEvent.class);

        verify(objectMapper).writeValueAsString(anyMap());
        verify(outboxService).saveWithRouting(
                eventCaptor.capture(),
                eq("IncomingDocument"),
                eq(testDocument.getId().toString()),
                eq("trace.document.received"),
                eq("corr-123"),
                headersJsonCaptor.capture()
        );

        DocumentReceivedTraceEvent captured = eventCaptor.getValue();
        assertThat(captured.getDocumentId()).isEqualTo(testDocument.getId().toString());
        assertThat(captured.getDocumentType()).isEqualTo("TAX_INVOICE");
        assertThat(captured.getDocumentNumber()).isEqualTo("INV-001");
        assertThat(captured.getStatus()).isEqualTo("VALIDATED");
        assertThat(headersJsonCaptor.getValue()).contains("corr-123").contains("TAX_INVOICE");
    }

    @Test
    @DisplayName("publishStartSagaCommand uses correlationId as partition key when present")
    void shouldUseCorrelationIdAsPartitionKey() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        eventPublisher.publishStartSagaCommand(testDocument, "<xml></xml>");

        verify(outboxService).saveWithRouting(any(), any(), any(),
                any(), eq("corr-123"), any());
    }

    @Test
    @DisplayName("publishStartSagaCommand falls back to documentId as partition key when correlationId is null")
    void shouldFallbackToDocumentIdAsPartitionKey() throws JsonProcessingException {
        IncomingDocument docWithoutCorrelation = IncomingDocument.builder()
                .documentNumber("INV-002")
                .xmlContent("<xml></xml>")
                .source("KAFKA")
                .correlationId(null)
                .documentType(DocumentType.INVOICE)
                .status(DocumentStatus.VALIDATED)
                .build();

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        eventPublisher.publishStartSagaCommand(docWithoutCorrelation, "<xml></xml>");

        verify(outboxService).saveWithRouting(any(), any(),
                eq(docWithoutCorrelation.getId().toString()),
                any(), eq(docWithoutCorrelation.getId().toString()), any());
    }

    @Test
    @DisplayName("publishStartSagaCommand handles JSON serialization failure gracefully")
    void shouldHandleJsonSerializationFailure() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("JSON error") {});

        eventPublisher.publishStartSagaCommand(testDocument, "<xml></xml>");

        ArgumentCaptor<String> headersJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(any(), any(), any(), any(), any(),
                headersJsonCaptor.capture());

        assertThat(headersJsonCaptor.getValue()).isNull();
    }
}
