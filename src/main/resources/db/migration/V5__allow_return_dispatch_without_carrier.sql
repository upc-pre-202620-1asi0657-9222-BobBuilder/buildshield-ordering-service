-- Un despacho de retorno nace en tránsito con los datos de la devolución de inventario, que no tiene transportista.
-- El resto de los despachos sigue sin poder salir sin transportista.
ALTER TABLE dispatches DROP CONSTRAINT ck_dispatches_carrier;
ALTER TABLE dispatches ADD CONSTRAINT ck_dispatches_carrier CHECK (status IN ('PREPARED', 'CANCELLED')
    OR (departed_at IS NOT NULL AND (type = 'RETURN'
        OR (carrier_name IS NOT NULL AND carrier_document IS NOT NULL AND plate IS NOT NULL))));
