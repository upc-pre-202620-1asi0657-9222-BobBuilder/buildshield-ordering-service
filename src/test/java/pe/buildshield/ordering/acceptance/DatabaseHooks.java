package pe.buildshield.ordering.acceptance;

import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.ordering.support.TestDatabase;

/** Antes de cada escenario: base vacía (tablas del servicio) e inventario simulado vacío. */
public class DatabaseHooks {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeInventory inventory;

    @Before(order = 0)
    public void resetState() {
        TestDatabase.reset(jdbc);
        inventory.reset();
    }
}
