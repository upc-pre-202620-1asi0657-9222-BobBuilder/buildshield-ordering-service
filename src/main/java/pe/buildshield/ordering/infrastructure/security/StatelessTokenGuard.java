package pe.buildshield.ordering.infrastructure.security;

import org.springframework.stereotype.Component;
import pe.buildshield.ordering.shared.security.RevokedTokenStore;

import java.util.UUID;

/**
 * Revocación de tokens para el filtro JWT del kernel compartido. Ordering no guarda una copia de los usuarios (no
 * consume {@code buildshield.iam}), así que no puede saber si un usuario fue desactivado o cambió de rol, ni ve la
 * lista de tokens cerrados con sign-out, que vive en iam. Por eso no bloquea ningún token: el token de acceso vence
 * en 15 minutos, el gateway y iam se encargan de la revocación, y aquí se sigue validando la firma, el emisor, el
 * vencimiento y el rol de cada endpoint (defensa en profundidad, QAS04). Las asignaciones vigentes
 * ({@code assignment_projection}) limitan además lo que cada encargado ve y hace.
 */
@Component
class StatelessTokenGuard implements RevokedTokenStore {

    @Override
    public boolean isRevoked(String tokenId) {
        return false;
    }

    @Override
    public boolean isUserBlocked(UUID userId, String role) {
        return false;
    }
}
