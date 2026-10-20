-- Copia local de los instrumentos de market-data-service, alimentada por el tópico instruments.events.
-- Solo lo que order-service necesita para aceptar o rechazar una orden: el estado ahora, y el tick para
-- cuando valide el precio (RF-16).
CREATE TABLE instrumento (
    simbolo        VARCHAR(10) PRIMARY KEY,
    estado         VARCHAR(10) NOT NULL CHECK (estado IN ('ACTIVO', 'SUSPENDIDO')),
    tick           BIGINT      NOT NULL CHECK (tick > 0),
    actualizado_en TIMESTAMPTZ NOT NULL
);
