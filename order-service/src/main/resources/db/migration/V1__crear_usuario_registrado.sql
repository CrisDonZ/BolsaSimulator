-- Copia local de los usuarios que existen en auth-service, alimentada por el tópico users.registered.
-- order-service no puede leer la base de auth-service (una base por servicio), por eso guarda la suya.
-- Solo el id y la fecha: los datos personales se quedan en auth-service.
CREATE TABLE usuario_registrado (
    usuario_id    UUID        PRIMARY KEY,
    registrado_en TIMESTAMPTZ NOT NULL
);
