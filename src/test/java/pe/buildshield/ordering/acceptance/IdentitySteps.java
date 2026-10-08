package pe.buildshield.ordering.acceptance;

import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import pe.buildshield.ordering.shared.security.JwtTokenIssuer;
import pe.buildshield.ordering.shared.tenant.TenantInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasos de identidad que en el Core resolvía iam. Ordering no guarda usuarios: la organización y los usuarios son solo
 * identificadores, y el "inicio de sesión" emite el JWT con la clave de prueba, igual que lo haría iam.
 */
public class IdentitySteps {

    @Autowired
    ScenarioSession session;

    @Autowired
    JwtTokenIssuer issuer;

    /** Usuarios del escenario por correo. */
    private final Map<String, TenantInfo> users = new HashMap<>();

    @Dado("que la organización {string} con RUC {string} está registrada con el administrador {string} y contraseña {string}")
    public void organizationRegistered(String legalName, String ruc, String adminEmail, String password) {
        session.organization(UUID.randomUUID());
        userCreated(adminEmail, "ADMINISTRATOR");
    }

    @Dado("que creé el usuario {string} con correo {string}, rol {string} y contraseña {string}")
    public void userCreated(String fullName, String email, String role, String password) {
        userCreated(email, role(role));
    }

    @Dado("que {string} inició sesión con la contraseña {string}")
    public void signedIn(String email, String password) {
        TenantInfo user = users.get(email);
        assertThat(user).as("el usuario %s no existe en el escenario", email).isNotNull();
        session.organization(user.organizationId());
        session.accessToken(issuer.issue(user).value());
    }

    @Entonces("la respuesta tiene estado {int}")
    public void responseStatus(int status) {
        session.expectStatus(status);
    }

    @Entonces("la operación es rechazada con estado {int}")
    public void rejected(int status) {
        session.expectStatus(status);
    }

    @Entonces("la operación es rechazada con estado {int} y código {string}")
    public void rejectedWithCode(int status, String code) throws Exception {
        session.expectStatus(status);
        assertThat(session.body().path("code").asText()).isEqualTo(code);
    }

    private void userCreated(String email, String role) {
        UUID userId = UUID.randomUUID();
        users.put(email, new TenantInfo(session.organization(), userId, role));
        session.put(email, userId);
    }

    /** "encargado de obra" → SITE_MANAGER; "encargado de almacén" → WAREHOUSE_MANAGER. */
    private static String role(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "administrador" -> "ADMINISTRATOR";
            case "encargado de obra" -> "SITE_MANAGER";
            case "encargado de almacén", "encargado de almacen" -> "WAREHOUSE_MANAGER";
            default -> displayName;
        };
    }
}
