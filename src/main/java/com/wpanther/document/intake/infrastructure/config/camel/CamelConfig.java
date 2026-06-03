package com.wpanther.document.intake.infrastructure.config.camel;

import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import com.wpanther.document.intake.domain.model.IncomingDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for document intake.
 * <p>
 * The Kafka consumer route receives documents from Kafka and delegates to
 * {@link SubmitDocumentUseCase}. Dead-letter channel handles failures after retries.
 * <p>
 * The REST path no longer uses Camel — the controller calls the use case directly.
 */
@Component
@Slf4j
public class CamelConfig extends RouteBuilder {

    private final SubmitDocumentUseCase submitDocumentUseCase;
    private final String documentIntakeTopic;
    private final String intakeDlqTopic;
    private final boolean kafkaConsumerAutoStartup;

    public CamelConfig(
            SubmitDocumentUseCase submitDocumentUseCase,
            @Value("${app.kafka.topics.invoice-intake}") String documentIntakeTopic,
            @Value("${app.kafka.topics.intake-dlq}") String intakeDlqTopic,
            @Value("${app.kafka.consumer.auto-startup:true}") boolean kafkaConsumerAutoStartup) {
        this.submitDocumentUseCase = submitDocumentUseCase;
        this.documentIntakeTopic = documentIntakeTopic;
        this.intakeDlqTopic = intakeDlqTopic;
        this.kafkaConsumerAutoStartup = kafkaConsumerAutoStartup;
    }

    @Override
    public void configure() {
        if (kafkaConsumerAutoStartup) {
            from("kafka:" + documentIntakeTopic + "?groupId=intake-service&autoCommitEnable=false")
                .errorHandler(deadLetterChannel("kafka:" + intakeDlqTopic)
                    .maximumRedeliveries(3)
                    .redeliveryDelay(1000)
                    .useExponentialBackOff()
                    .logExhausted(true))
                .routeId("document-intake-kafka")
                .log(LoggingLevel.INFO, "Received document from Kafka: ${header[kafka.KEY]}")
                .process(exchange -> {
                    String xmlContent = exchange.getIn().getBody(String.class);
                    String correlationId = exchange.getIn().getHeader("kafka.KEY", String.class);

                    IncomingDocument document = submitDocumentUseCase.submitDocument(
                        xmlContent, "KAFKA", correlationId);

                    exchange.getIn().setHeader("documentId", document.getId().toString());
                    exchange.getIn().setHeader("documentType", document.getDocumentType().name());
                    exchange.getIn().setHeader("isValid", document.isValid());
                })
                .log(LoggingLevel.INFO, "Document processed: documentId=${header.documentId}, isValid=${header.isValid}");
        }
    }
}
