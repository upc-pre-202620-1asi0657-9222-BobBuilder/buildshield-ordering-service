package pe.buildshield.ordering.acceptance;

import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;

/**
 * Existencias en el inventario simulado ({@link FakeInventory}). Los movimientos llegan por eventos: cada
 * verificación espera a que el valor se alcance y se mantenga medio segundo (no se suma dos veces).
 */
public class InventorySteps {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ScenarioSession session;

    @Autowired
    FakeInventory inventory;

    @Dado("que el almacén {string} tiene {string} del material {string}")
    public void warehouseHasStock(String warehouse, String quantity, String sku) {
        inventory.setAvailable(session.id(warehouse), session.id(sku.toUpperCase()), new BigDecimal(quantity));
    }

    @Entonces("el stock disponible del material {string} en el almacén {string} es {string}")
    public void availableStockIs(String sku, String warehouse, String expected) {
        settlesAt(() -> inventory.available(session.id(warehouse), session.id(sku.toUpperCase())), expected);
    }

    @Entonces("el stock reservado del material {string} en el almacén {string} es {string}")
    public void reservedStockIs(String sku, String warehouse, String expected) {
        settlesAt(() -> inventory.reserved(session.id(warehouse), session.id(sku.toUpperCase())), expected);
    }

    @Entonces("el stock del material {string} en la obra {string} es {string}")
    public void worksiteStockIs(String sku, String worksite, String expected) {
        settlesAt(() -> inventory.available(session.id(worksite), session.id(sku.toUpperCase())), expected);
    }

    private static void settlesAt(Supplier<BigDecimal> value, String expected) {
        BigDecimal target = new BigDecimal(expected);
        await().atMost(TIMEOUT).pollInSameThread().during(Duration.ofMillis(600)).pollInterval(Duration.ofMillis(100))
                .until(() -> value.get().compareTo(target) == 0);
    }
}
