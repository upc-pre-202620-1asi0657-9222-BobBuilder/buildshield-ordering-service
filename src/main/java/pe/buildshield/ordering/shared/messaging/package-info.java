/**
 * Eventos de integración entre microservicios: sobre JSON común ({@link pe.buildshield.ordering.shared.messaging.EventEnvelope}),
 * Transactional Outbox ({@link pe.buildshield.ordering.shared.messaging.OutboxWriter} y
 * {@link pe.buildshield.ordering.shared.messaging.OutboxPublisher}) y consumo idempotente
 * ({@link pe.buildshield.ordering.shared.messaging.IdempotentEventProcessor}). Entrega al menos una vez.
 */
package pe.buildshield.ordering.shared.messaging;
