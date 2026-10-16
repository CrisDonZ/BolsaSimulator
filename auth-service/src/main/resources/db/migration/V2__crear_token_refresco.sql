-- Tabla TOKEN_REFRESCO del modelo entidad-relación (auth-service, HU-02).
-- Guarda solo el hash SHA-256 del token: quien lea la base no puede usar los tokens.
CREATE TABLE token_refresco (
    id         UUID         PRIMARY KEY,
    usuario_id UUID         NOT NULL REFERENCES usuario (id),
    token_hash VARCHAR(100) NOT NULL UNIQUE,
    expira_en  TIMESTAMPTZ  NOT NULL,
    revocado   BOOLEAN      NOT NULL DEFAULT false
);

-- Para encontrar rápido las sesiones de un usuario (por ejemplo, al bloquearlo en HU-26)
CREATE INDEX idx_token_refresco_usuario ON token_refresco (usuario_id);
