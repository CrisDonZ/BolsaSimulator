-- Tabla ORDEN del modelo entidad-relación (order-service). Ninguna orden se borra (regla 9).
-- La base de datos es la última barrera: las reglas también se validan aquí, no solo en Java.
-- clave_idempotencia llega con HU-10.
CREATE TABLE orden (
    id                 UUID          PRIMARY KEY,
    usuario_id         UUID          NOT NULL,
    simbolo            VARCHAR(10)   NOT NULL,
    lado               VARCHAR(6)    NOT NULL CHECK (lado IN ('COMPRA', 'VENTA')),
    tipo               VARCHAR(7)    NOT NULL CHECK (tipo IN ('MERCADO', 'LIMITE')),
    precio_limite      BIGINT        CHECK (precio_limite > 0),
    cantidad           INTEGER       NOT NULL CHECK (cantidad > 0),
    cantidad_ejecutada INTEGER       NOT NULL,
    precio_promedio    NUMERIC(18,4),
    estado             VARCHAR(10)   NOT NULL
        CHECK (estado IN ('PENDIENTE', 'ABIERTA', 'PARCIAL', 'EJECUTADA', 'CANCELADA', 'RECHAZADA')),
    motivo             VARCHAR(200),
    creada_en          TIMESTAMPTZ   NOT NULL,
    actualizada_en     TIMESTAMPTZ   NOT NULL,
    -- Bloqueo optimista (regla 5): dos cambios a la vez sobre la misma orden no se pisan
    version            INTEGER       NOT NULL,
    -- Regla 8: el precio límite es obligatorio en LIMITE y no existe en MERCADO...
    CHECK ((tipo = 'LIMITE' AND precio_limite IS NOT NULL) OR (tipo = 'MERCADO' AND precio_limite IS NULL)),
    -- ...y nunca se ejecuta más de lo pedido
    CHECK (cantidad_ejecutada BETWEEN 0 AND cantidad)
);

-- Paginación por cursor: cada índice tiene las columnas en el mismo orden que la consulta
-- (usuario, [estado,] creada_en DESC, id DESC), así PostgreSQL salta directo a la página y lee las filas ya
-- ordenadas, sin OFFSET ni ordenar en memoria. Ver ConsultaOrdenes.

-- Todas las órdenes del usuario
CREATE INDEX orden_usuario_creada ON orden (usuario_id, creada_en DESC, id DESC);

-- Un estado en particular. Reemplaza el índice (usuario_id, estado) del modelo: empieza igual, así que
-- sirve para lo mismo
CREATE INDEX orden_usuario_estado_creada ON orden (usuario_id, estado, creada_en DESC, id DESC);

-- Las abiertas (ABIERTA y PARCIAL), la consulta de todos los días. Parcial: solo guarda las órdenes vivas,
-- así se mantiene pequeño aunque el historial crezca
CREATE INDEX orden_usuario_abiertas ON orden (usuario_id, creada_en DESC, id DESC)
    WHERE estado IN ('ABIERTA', 'PARCIAL');
