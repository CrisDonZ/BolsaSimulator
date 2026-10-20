-- Tabla EJECUCION del modelo entidad-relación: cada parte ejecutada de una orden, según orders.events.
-- Con ella se calcula el precio promedio de la orden.
CREATE TABLE ejecucion (
    id           UUID        PRIMARY KEY,
    orden_id     UUID        NOT NULL REFERENCES orden (id),
    -- La operación de trades.executed de la que sale esta ejecución
    operacion_id UUID        NOT NULL,
    precio       BIGINT      NOT NULL CHECK (precio > 0),
    cantidad     INTEGER     NOT NULL CHECK (cantidad > 0),
    ejecutada_en TIMESTAMPTZ NOT NULL,
    -- Una operación se registra una sola vez por orden, aunque su evento llegue repetido (segunda barrera)
    UNIQUE (orden_id, operacion_id)
);

-- Tabla EVENTO_PROCESADO del modelo (regla 6, RNF-09): el id de cada evento ya aplicado, guardado en la misma
-- transacción que el cambio. Si el evento vuelve a llegar, se ignora.
CREATE TABLE evento_procesado (
    id_evento    UUID        PRIMARY KEY,
    procesado_en TIMESTAMPTZ NOT NULL
);
