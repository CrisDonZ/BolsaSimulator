-- Tabla INSTRUMENTO del modelo entidad-relación (market-data-service).
-- La base de datos es la última barrera: las reglas también se validan aquí, no solo en Java.
CREATE TABLE instrumento (
    simbolo           VARCHAR(10)  PRIMARY KEY CHECK (simbolo ~ '^[A-Z]{3,5}$'),
    nombre            VARCHAR(100) NOT NULL,
    sector            VARCHAR(50)  NOT NULL,
    precio_inicial    BIGINT       NOT NULL CHECK (precio_inicial > 0),
    tick              BIGINT       NOT NULL CHECK (tick > 0),
    acciones_emitidas BIGINT       NOT NULL CHECK (acciones_emitidas > 0),
    estado            VARCHAR(10)  NOT NULL CHECK (estado IN ('ACTIVO', 'SUSPENDIDO')),
    creado_en         TIMESTAMPTZ  NOT NULL,
    -- No está en el modelo: va en cada evento para que la copia de otro servicio ignore uno más viejo
    actualizado_en    TIMESTAMPTZ  NOT NULL,
    -- El precio inicial también es un precio, así que respeta el tick
    CHECK (precio_inicial % tick = 0)
);
