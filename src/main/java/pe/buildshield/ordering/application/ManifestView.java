package pe.buildshield.ordering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Manifiesto del despacho (US24) con el QR en PNG base64 que codifica su código. */
public record ManifestView(String manifestCode, DispatchView dispatch, UUID orderId, Instant orderPlacedAt,
        Site worksite, Site warehouse, List<Line> lines, String qrCodePngBase64, String qrContent) {

    public record Site(UUID id, String name, String address) {
    }

    public record Line(UUID materialId, String sku, String name, String unit, BigDecimal quantity) {
    }
}
