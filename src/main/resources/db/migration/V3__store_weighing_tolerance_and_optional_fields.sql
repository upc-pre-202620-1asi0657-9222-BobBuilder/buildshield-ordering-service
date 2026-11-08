-- El equipo amplió el ERD: la foto del pesaje de salida y la fecha requerida son opcionales, y el pesaje de llegada
-- guarda la tolerancia con la que se evaluó.

-- La foto ausente vuelve a ser nula. V2 la había marcado con un valor fijo que ya no existe en el código.
ALTER TABLE departure_weighings ALTER COLUMN ticket_photo_url DROP NOT NULL;
UPDATE departure_weighings SET ticket_photo_url = NULL WHERE ticket_photo_url = 'sin-foto';

-- Las fechas que V2 completó con el día de creación no se tocan: ya no se distinguen de las fechas reales.
ALTER TABLE orders ALTER COLUMN required_date DROP NOT NULL;

-- Queda nula en los pesajes anteriores a esta migración: su tolerancia original no se conservó.
ALTER TABLE arrival_weighings ADD COLUMN tolerance_pct NUMERIC(5, 2);
