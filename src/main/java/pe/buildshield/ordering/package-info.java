/**
 * Microservicio Gestión de Pedidos y Entregas (contexto core): pedidos de materiales, despachos del almacén a
 * la obra y recepción en obra. Publica en {@code buildshield.ordering} y consume {@code buildshield.organization}
 * (proyecciones de obras, almacenes, materiales y asignaciones) y {@code buildshield.inventory} (respuesta de la
 * saga de reserva de stock).
 *
 * <p>Capas: {@code interfaces} (REST) → {@code application} (casos de uso) → {@code domain} (Java puro);
 * {@code infrastructure} implementa los puertos (JPA, JDBC, Kafka, QR, evidencias). {@code shared} es el kernel
 * compartido copiado de buildshield-iam-service.
 */
package pe.buildshield.ordering;
