-- Tabla OUTBOX del modelo entidad-relación (regla 7, RNF-12). El cambio de negocio y su evento se guardan en
-- la misma transacción; PublicadorOutbox los envía a Kafka después y marca publicado_en.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    topico       VARCHAR(100) NOT NULL,
    clave        VARCHAR(100) NOT NULL,
    tipo_evento  VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    creado_en    TIMESTAMPTZ  NOT NULL,
    publicado_en TIMESTAMPTZ
);

-- Solo los pendientes y en el orden en que se publican: revisar el outbox vacío cada 100 ms casi no cuesta,
-- aunque la tabla tenga millones de filas ya publicadas
CREATE INDEX outbox_pendientes ON outbox (creado_en, id) WHERE publicado_en IS NULL;
