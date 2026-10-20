-- Clave de idempotencia de la orden (HU-10, RF-22): el cliente envía una clave única por cada orden que quiere
-- crear (header Idempotency-Key). Si la misma solicitud llega dos veces, la segunda recibe la orden original.
-- Las órdenes de antes de esta migración quedan con NULL, que el índice único permite.
ALTER TABLE orden ADD COLUMN clave_idempotencia VARCHAR(64);

-- La clave es por usuario: dos usuarios pueden usar la misma sin chocar. Es la última barrera: si dos
-- solicitudes con la misma clave llegan a la vez, PostgreSQL hace esperar a la segunda hasta que la primera
-- termine y, si la primera se guardó, la rechaza. El nombre lo reconoce ServicioOrdenes.
CREATE UNIQUE INDEX orden_usuario_clave ON orden (usuario_id, clave_idempotencia);
