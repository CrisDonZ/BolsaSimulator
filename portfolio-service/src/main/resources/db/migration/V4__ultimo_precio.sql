-- Último precio de cada símbolo, para valorar el portafolio (HU-11, RF-32, RF-33): el de la última operación de
-- trades.executed. Si un símbolo todavía no tiene operaciones, el portafolio usa el precio inicial de EMISION.
-- Es una copia local, como la de order-service (V7 de order-service): portfolio-service no le pregunta el precio a
-- otro servicio en cada consulta.
CREATE TABLE ultimo_precio (
    simbolo        VARCHAR(10) PRIMARY KEY,
    precio         BIGINT      NOT NULL CHECK (precio > 0),
    -- Cuándo fue esa operación (ejecutadaEn de trades.executed): una operación más vieja que la copia no la reemplaza
    actualizado_en TIMESTAMPTZ NOT NULL
);
