-- Ventas con reserva de acciones y oferta inicial (HU-06, RF-17, RF-30, RF-31, RF-59). Con esto la contabilidad
-- es de doble partida (RNF-08): el dinero de todas las cuentas no cambia con las operaciones, y las acciones de
-- cada símbolo suman siempre sus acciones emitidas.

-- El rol que llega en users.registered: INVERSIONISTA o BOT. NULL si la cuenta se abrió antes de que llegara su
-- evento (con la primera consulta o la primera orden); se completa cuando llega
ALTER TABLE cuenta ADD COLUMN rol VARCHAR(15) CHECK (rol IN ('INVERSIONISTA', 'BOT'));
-- El bot creador de mercado, que recibe las acciones emitidas de cada instrumento (RF-59)
ALTER TABLE cuenta ADD COLUMN creador_mercado BOOLEAN NOT NULL DEFAULT false;
-- Uno solo: si llegaran dos, las acciones emitidas no tendrían un único dueño
CREATE UNIQUE INDEX cuenta_un_creador_de_mercado ON cuenta (creador_mercado) WHERE creador_mercado;

-- La oferta inicial de cada símbolo: se emite una sola vez, la primera vez que llega su instruments.events.
-- Si todavía no existe la cuenta del creador de mercado, queda PENDIENTE y se aplica cuando llegue
CREATE TABLE emision (
    simbolo           VARCHAR(10) PRIMARY KEY,
    acciones_emitidas BIGINT      NOT NULL CHECK (acciones_emitidas > 0),
    precio_inicial    BIGINT      NOT NULL CHECK (precio_inicial > 0),
    estado            VARCHAR(10) NOT NULL CHECK (estado IN ('PENDIENTE', 'APLICADA')),
    -- A quién se le acreditaron; NULL mientras está PENDIENTE
    cuenta_id         UUID        REFERENCES cuenta (id),
    creada_en         TIMESTAMPTZ NOT NULL,
    aplicada_en       TIMESTAMPTZ,
    CHECK ((estado = 'APLICADA') = (cuenta_id IS NOT NULL AND aplicada_en IS NOT NULL))
);

-- La posición del creador de mercado recibe todas las acciones emitidas de una vez, y acciones_emitidas es BIGINT
-- en market-data-service: con INTEGER, una emisión de más de 2.147 millones no cabría
ALTER TABLE posicion ALTER COLUMN cantidad TYPE BIGINT;
ALTER TABLE posicion ALTER COLUMN cantidad_reservada TYPE BIGINT;

-- EMISION: las acciones que recibe el creador de mercado. Monto 0: no entra ni sale dinero
ALTER TABLE movimiento DROP CONSTRAINT movimiento_tipo_check;
ALTER TABLE movimiento ADD CONSTRAINT movimiento_tipo_check
    CHECK (tipo IN ('SALDO_INICIAL', 'RESERVA', 'LIBERACION', 'COMPRA', 'VENTA', 'EMISION', 'REINICIO'));
ALTER TABLE movimiento ALTER COLUMN cantidad TYPE BIGINT;

-- Conciliación (regla 4): el saldo_reservado es la suma de las reservas ACTIVAS de dinero; las de acciones se
-- concilian con posicion.cantidad_reservada
DROP INDEX reserva_cuenta_activas;
CREATE INDEX reserva_cuenta_dinero_activas ON reserva (cuenta_id) WHERE estado = 'ACTIVA' AND tipo = 'DINERO';
