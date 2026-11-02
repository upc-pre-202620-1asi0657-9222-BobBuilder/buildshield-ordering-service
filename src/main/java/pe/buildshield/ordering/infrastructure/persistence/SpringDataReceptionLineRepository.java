package pe.buildshield.ordering.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface SpringDataReceptionLineRepository extends JpaRepository<ReceptionLineJpaEntity, UUID> {

    /** Filas [orderLineId, lo ya ingresado a la obra]. */
    @Query("""
            SELECT l.dispatchLine.orderLineId, SUM(l.stockedQty) FROM ReceptionLineJpaEntity l
            WHERE l.dispatchLine.orderLineId IN :orderLineIds GROUP BY l.dispatchLine.orderLineId""")
    List<Object[]> sumStockedByOrderLine(Collection<UUID> orderLineIds);
}
