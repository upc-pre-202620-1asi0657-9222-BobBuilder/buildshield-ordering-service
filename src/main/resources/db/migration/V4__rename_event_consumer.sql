-- Los dos consumidores de eventos pasan a ser un solo componente, StockEventConsumer, como en el C4. Lo ya procesado
-- se conserva con el nombre nuevo para que una reentrega se siga descartando.
INSERT INTO processed_events (event_id, consumer, processed_at)
SELECT event_id, 'StockEventConsumer', MIN(processed_at)
FROM processed_events
WHERE consumer IN ('InventoryEventConsumer', 'OrganizationEventConsumer')
GROUP BY event_id
ON CONFLICT (event_id, consumer) DO NOTHING;

DELETE FROM processed_events WHERE consumer IN ('InventoryEventConsumer', 'OrganizationEventConsumer');
