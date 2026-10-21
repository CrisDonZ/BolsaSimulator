-- Precio de referencia de cada instrumento, para validar el precio de las órdenes (RF-16) y reservar las compras a
-- mercado (RF-17): el de la última operación (trades.executed) o, si todavía no hay ninguna, el precio inicial
-- (instruments.events).
-- precio_inicial queda NULL en las copias de antes de esta migración hasta que llegue otro evento del instrumento;
-- market-data-service vuelve a publicar todo el catálogo cada vez que arranca.
ALTER TABLE instrumento ADD COLUMN precio_inicial BIGINT CHECK (precio_inicial > 0);
ALTER TABLE instrumento ADD COLUMN ultimo_precio BIGINT CHECK (ultimo_precio > 0);
-- Cuándo fue esa operación: una operación más vieja que la copia no la reemplaza
ALTER TABLE instrumento ADD COLUMN ultimo_precio_en TIMESTAMPTZ;
