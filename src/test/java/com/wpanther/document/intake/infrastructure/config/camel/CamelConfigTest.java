package com.wpanther.document.intake.infrastructure.config.camel;

import com.wpanther.document.intake.application.usecase.SubmitDocumentUseCase;
import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying CamelConfig initialises cleanly.
 * The direct:document-intake route has been removed; the controller calls the use case directly.
 * The Kafka consumer route is disabled in this test via auto-startup=false.
 * Kafka route behaviour (DLQ, retry, document processing) is covered by DocumentIntakeCdcIT.
 */
@CamelSpringBootTest
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    KafkaAutoConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.kafka.topics.invoice-intake=document.intake",
    "app.kafka.topics.intake-dlq=document.intake.dlq",
    "app.kafka.bootstrap-servers=localhost:9092",
    "app.kafka.consumer.auto-startup=false",
    "app.rate-limit.enabled=false",
    "camel.springboot.main-run-controller=false",
    "camel.springboot.xml-routes=false"
})
@ComponentScan(basePackages = {
    "com.wpanther.document.intake.infrastructure.config.camel"
})
@DisplayName("CamelConfig Integration Tests")
class CamelConfigTest {

    @Autowired
    private CamelContext camelContext;

    @MockBean
    private SubmitDocumentUseCase submitDocumentUseCase;

    @Test
    @DisplayName("CamelContext starts successfully with no routes (Kafka disabled)")
    void testCamelContextStarts() {
        assertThat(camelContext).isNotNull();
        assertThat(camelContext.isStarted()).isTrue();
    }
}
