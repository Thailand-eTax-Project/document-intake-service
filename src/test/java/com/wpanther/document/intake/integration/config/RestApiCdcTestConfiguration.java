package com.wpanther.document.intake.integration.config;

import org.apache.camel.spring.boot.CamelAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Test configuration for REST API CDC integration tests.
 * <p>
 * Excludes KafkaAutoConfiguration to prevent Spring from auto-creating Kafka consumer beans.
 * The Kafka consumer is also disabled via {@code app.kafka.consumer.auto-startup=false}.
 * <p>
 * CamelAutoConfiguration is included so that the Kafka Camel route definition is loaded
 * (even though the consumer is disabled). The REST controller calls the use case directly
 * and no longer depends on ProducerTemplate or the direct:document-intake route.
 */
@TestConfiguration
@EnableAutoConfiguration(exclude = {
    KafkaAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = {
    "com.wpanther.document.intake.infrastructure.adapter.out.persistence"
})
@EntityScan(basePackages = {
    "com.wpanther.document.intake.infrastructure.adapter.out.persistence"
})
@ComponentScan(
    basePackages = {
        "com.wpanther.document.intake.domain",
        "com.wpanther.document.intake.application.service",
        "com.wpanther.document.intake.infrastructure.adapter.out.persistence",
        "com.wpanther.document.intake.infrastructure.validation",
        "com.wpanther.document.intake.infrastructure.messaging",
        "com.wpanther.document.intake.infrastructure.config",
        "com.wpanther.saga.infrastructure"
    },
    // NOTE: Controllers are INCLUDED (unlike CdcTestConfiguration which excludes them)
    excludeFilters = {
        // No exclusions - include everything needed for REST API + direct Camel route
    }
)
@EnableTransactionManagement
@Import({TestKafkaConsumerConfig.class, CamelAutoConfiguration.class, FlywayAutoConfiguration.class})
public class RestApiCdcTestConfiguration {
}
