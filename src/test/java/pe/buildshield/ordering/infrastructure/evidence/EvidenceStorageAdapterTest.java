package pe.buildshield.ordering.infrastructure.evidence;

import org.junit.jupiter.api.Test;
import pe.buildshield.ordering.shared.error.ValidationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceStorageAdapterTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final String HOST = "https://evidencias.example/";

    private final EvidenceStorageAdapter adapter = new EvidenceStorageAdapter();

    @Test
    void a_photo_is_optional_and_is_kept_trimmed() {
        assertThat(adapter.registerIncidentPhoto(OWNER, null)).isNull();
        assertThat(adapter.registerIncidentPhoto(OWNER, "  ")).isNull();
        assertThat(adapter.registerIncidentPhoto(OWNER, " " + HOST + "foto.jpg ")).isEqualTo(HOST + "foto.jpg");
        assertThat(adapter.registerTicketPhoto(OWNER, null)).isNull();
        assertThat(adapter.registerTicketPhoto(OWNER, HOST + "ticket.jpg")).isEqualTo(HOST + "ticket.jpg");
    }

    @Test
    void only_http_urls_are_accepted() {
        assertThatThrownBy(() -> adapter.registerIncidentPhoto(OWNER, "ftp://evidencias.example/foto.jpg"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("incidencia");
        assertThatThrownBy(() -> adapter.registerIncidentPhoto(OWNER, "no es una url"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> adapter.registerTicketPhoto(OWNER, "foto.jpg"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("ticket");
    }

    @Test
    void an_incident_photo_fits_its_column_and_a_ticket_photo_fits_its_own() {
        String atLimit = HOST + "x".repeat(EvidenceStorageAdapter.INCIDENT_MAX_LENGTH - HOST.length());
        assertThat(adapter.registerIncidentPhoto(OWNER, atLimit)).isEqualTo(atLimit);
        assertThatThrownBy(() -> adapter.registerIncidentPhoto(OWNER, atLimit + "x"))
                .isInstanceOf(ValidationException.class);
        assertThat(adapter.registerTicketPhoto(OWNER, atLimit + "x")).isEqualTo(atLimit + "x");
    }
}
