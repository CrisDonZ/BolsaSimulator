-- Tabla OPERACION del modelo entidad-relación: cada operación que el motor publica en trades.executed (RF-28).
-- De aquí salen el último precio y la variación del día de cada instrumento (HU-14, RF-11, RF-40); después, las
-- velas (HU-15) y las últimas operaciones (HU-16).
CREATE TABLE operacion (
    -- El operacionId del evento: si Kafka entrega la misma operación dos veces, la segunda choca con esta llave
    -- y no se guarda (ON CONFLICT DO NOTHING)
    id              UUID        PRIMARY KEY,
    simbolo         VARCHAR(10) NOT NULL REFERENCES instrumento (simbolo),
    -- Orden de llegada dentro del instrumento (1, 2, 3...). La asigna market-data-service al guardar: el evento
    -- no la trae, el orden lo da la partición de Kafka (clave = símbolo)
    secuencia       BIGINT      NOT NULL,
    precio          BIGINT      NOT NULL CHECK (precio > 0),
    cantidad        INTEGER     NOT NULL CHECK (cantidad > 0),
    -- Referencias lógicas a ORDEN, que vive en order-service: no pueden ser llaves foráneas
    orden_compra_id UUID        NOT NULL,
    orden_venta_id  UUID        NOT NULL,
    ejecutada_en    TIMESTAMPTZ NOT NULL,
    UNIQUE (simbolo, secuencia)
);

-- La última operación de un símbolo, y la última anterior a una fecha (el precio de apertura del día)
CREATE INDEX operacion_simbolo_ejecutada_en ON operacion (simbolo, ejecutada_en DESC);
