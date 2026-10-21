-- Reservas y liquidación de las compras (HU-05, RF-17, RF-30, RF-31). Tablas RESERVA, POSICION y OUTBOX del modelo
-- entidad-relación. Como en V1, la base es la última barrera: ninguna reserva gasta o libera más de lo reservado y
-- ninguna posición queda negativa (RNF-07).

-- El dinero o las acciones bloqueadas de una orden, desde que se acepta hasta que la orden termina.
CREATE TABLE reserva (
    id              UUID        PRIMARY KEY,
    cuenta_id       UUID        NOT NULL REFERENCES cuenta (id),
    -- Una reserva por orden: la misma solicitud dos veces no reserva dos veces (segunda barrera de RNF-09)
    orden_id        UUID        NOT NULL UNIQUE,
    tipo            VARCHAR(10) NOT NULL CHECK (tipo IN ('DINERO', 'ACCIONES')),
    -- La acción reservada; NULL si se reserva dinero
    simbolo         VARCHAR(10) CHECK ((tipo = 'DINERO') = (simbolo IS NULL)),
    -- Precio máximo de una compra LIMITE: con él se libera, en cada ejecución, lo que se pagó de menos. NULL en
    -- una compra a mercado, que libera lo que sobra cuando termina
    precio_limite   BIGINT      CHECK (precio_limite > 0),
    monto_reservado BIGINT      NOT NULL CHECK (monto_reservado > 0),
    -- Lo ya pagado en operaciones y lo ya devuelto al disponible. Lo que falta por usar es
    -- monto_reservado - monto_consumido - monto_liberado
    monto_consumido BIGINT      NOT NULL CHECK (monto_consumido >= 0),
    monto_liberado  BIGINT      NOT NULL CHECK (monto_liberado >= 0),
    estado          VARCHAR(10) NOT NULL CHECK (estado IN ('ACTIVA', 'CONSUMIDA', 'LIBERADA')),
    creada_en       TIMESTAMPTZ NOT NULL,
    actualizada_en  TIMESTAMPTZ NOT NULL,
    CHECK (monto_consumido + monto_liberado <= monto_reservado),
    -- Una reserva que terminó no tiene nada pendiente: todo se pagó o se devolvió
    CHECK (estado = 'ACTIVA' OR monto_consumido + monto_liberado = monto_reservado)
);

-- Conciliación (regla 4): la suma de las reservas ACTIVAS de dinero de una cuenta es su saldo_reservado
CREATE INDEX reserva_cuenta_activas ON reserva (cuenta_id) WHERE estado = 'ACTIVA';

-- Las acciones de cada usuario. Las crea la primera compra de ese símbolo.
CREATE TABLE posicion (
    id                 UUID          PRIMARY KEY,
    cuenta_id          UUID          NOT NULL REFERENCES cuenta (id),
    simbolo            VARCHAR(10)   NOT NULL,
    cantidad           INTEGER       NOT NULL CHECK (cantidad >= 0),
    -- Acciones bloqueadas en órdenes de venta (llegan con HU-06)
    cantidad_reservada INTEGER       NOT NULL,
    -- Precio promedio de compra, con 4 decimales como el precio promedio de las órdenes
    costo_promedio     NUMERIC(18,4) NOT NULL CHECK (costo_promedio >= 0),
    version            INTEGER       NOT NULL,
    UNIQUE (cuenta_id, simbolo),
    CHECK (cantidad_reservada BETWEEN 0 AND cantidad)
);

-- Tabla OUTBOX (regla 7, RNF-12), igual que la de order-service: la respuesta de cada reserva se guarda en la
-- misma transacción que la reserva, y PublicadorOutbox la envía a reservations.events después.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    topico       VARCHAR(100) NOT NULL,
    clave        VARCHAR(100) NOT NULL,
    tipo_evento  VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    creado_en    TIMESTAMPTZ  NOT NULL,
    publicado_en TIMESTAMPTZ
);

CREATE INDEX outbox_pendientes ON outbox (creado_en, id) WHERE publicado_en IS NULL;
