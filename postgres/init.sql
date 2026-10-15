-- Se ejecuta solo la primera vez que arranca el contenedor de PostgreSQL.
-- Una base por servicio: ningún servicio lee las tablas de otro (ver el modelo entidad-relación).
CREATE DATABASE auth;
