package pe.buildshield.ordering.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.ordering.shared.config.BuildshieldProperties;
import pe.buildshield.ordering.shared.messaging.OutboxPublisher;

import java.time.Clock;

/**
 * Productor Kafka de ordering: cada 500 ms envía al tópico {@code buildshield.ordering} los eventos pendientes del
 * outbox (pedidos, despachos, incidencias y recepciones; ver {@code OrderingEvents}).
 */
@Component
public class OrderingOutboxPublisher extends OutboxPublisher {

    public OrderingOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            PlatformTransactionManager transactionManager, Clock clock, BuildshieldProperties properties) {
        super(jdbc, kafka, json, new TransactionTemplate(transactionManager), clock,
                properties.getMessaging().getTopic(), properties.getMessaging().getBatchSize(),
                properties.getMessaging().getSendTimeout());
    }

    @Scheduled(fixedDelayString = "${buildshield.messaging.outbox-interval:PT0.5S}")
    public void publish() {
        publishPending();
    }
}
