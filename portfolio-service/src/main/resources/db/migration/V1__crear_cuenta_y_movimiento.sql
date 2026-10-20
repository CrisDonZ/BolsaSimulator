-- Tablas CUENTA y MOVIMIENTO del modelo entidad-relación (portfolio-service). Montos en COP enteros (regla 2).
-- La base de datos es la última barrera: aunque el servicio falle, ningún saldo queda negativo (RNF-07, regla 3).
CREATE TABLE cuenta (
    id               UUID        PRIMARY KEY,
    -- Una cuenta por usuario: el evento users.registered y la primera consulta no pueden crear dos
    usuario_id       UUID        NOT NULL UNIQUE,
    saldo_inicial    BIGINT      NOT NULL CHECK (saldo_inicial >= 0),
    saldo_disponible BIGINT      NOT NULL CHECK (saldo_disponible >= 0),
    saldo_reservado  BIGINT      NOT NULL CHECK (saldo_reservado >= 0),
    -- Bloqueo optimista (regla 5): dos cambios a la vez sobre la misma cuenta no se pisan
    version          INTEGER     NOT NULL,
    actualizado_en   TIMESTAMPTZ NOT NULL
);

-- Cada entrada o salida de dinero, con fecha y orden u operación de origen (RF-09). Nunca se borra (regla 9).
CREATE TABLE movimiento (
    id            UUID        PRIMARY KEY,
    cuenta_id     UUID        NOT NULL REFERENCES cuenta (id),
    tipo          VARCHAR(15) NOT NULL
        CHECK (tipo IN ('SALDO_INICIAL', 'RESERVA', 'LIBERACION', 'COMPRA', 'VENTA', 'REINICIO')),
    -- Positivo entra, negativo sale
    monto         BIGINT      NOT NULL,
    simbolo       VARCHAR(10),
    cantidad      INTEGER,
    referencia_id UUID,
    creado_en     TIMESTAMPTZ NOT NULL
);

-- Paginación por cursor, como la lista de órdenes: cada índice tiene las columnas en el orden de la consulta
-- (cuenta, [tipo,] creado_en DESC, id DESC). El rango de fechas es un rango sobre creado_en dentro del mismo
-- índice. Ver ConsultaMovimientos.
CREATE INDEX movimiento_cuenta_creado ON movimiento (cuenta_id, creado_en DESC, id DESC);
CREATE INDEX movimiento_cuenta_tipo_creado ON movimiento (cuenta_id, tipo, creado_en DESC, id DESC);

-- Tabla EVENTO_PROCESADO (regla 6, RNF-09): los eventos de Kafka ya aplicados.
CREATE TABLE evento_procesado (
    id_evento    UUID        PRIMARY KEY,
    procesado_en TIMESTAMPTZ NOT NULL
);
