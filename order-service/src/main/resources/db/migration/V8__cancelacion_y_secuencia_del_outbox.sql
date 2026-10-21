-- Cancelar una orden (HU-08, RF-19). Cuándo pidió el usuario la cancelación: NULL si nunca la pidió. Con ella,
-- una segunda solicitud responde lo mismo sin publicar otro CANCELAR. El estado no cambia hasta que el motor
-- confirma con CANCELADA en orders.events.
ALTER TABLE orden ADD COLUMN cancelacion_solicitada_en TIMESTAMPTZ;

-- El orden del outbox: antes se ordenaba por (creado_en, id), y con dos filas de la misma marca de tiempo el UUID
-- aleatorio decidía cuál salía primero. La secuencia nunca empata. Las filas que ya existen la reciben al migrar.
ALTER TABLE outbox ADD COLUMN secuencia BIGINT GENERATED ALWAYS AS IDENTITY;

DROP INDEX outbox_pendientes;
CREATE INDEX outbox_pendientes ON outbox (secuencia) WHERE publicado_en IS NULL;
