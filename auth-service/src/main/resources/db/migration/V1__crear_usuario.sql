-- Tabla USUARIO del modelo entidad-relación (auth-service).
-- La base de datos es la última barrera: las reglas también se validan aquí, no solo en Java.
CREATE TABLE usuario (
    id                       UUID         PRIMARY KEY,
    nombre                   VARCHAR(100) NOT NULL,
    alias                    VARCHAR(30)  NOT NULL UNIQUE,
    correo                   VARCHAR(150) NOT NULL UNIQUE,
    password_hash            VARCHAR(100) NOT NULL,
    rol                      VARCHAR(20)  NOT NULL CHECK (rol IN ('INVERSIONISTA', 'ADMIN', 'BOT')),
    estado                   VARCHAR(20)  NOT NULL CHECK (estado IN ('ACTIVO', 'BLOQUEADO')),
    fecha_autorizacion_datos TIMESTAMPTZ  NOT NULL,
    creado_en                TIMESTAMPTZ  NOT NULL
);
