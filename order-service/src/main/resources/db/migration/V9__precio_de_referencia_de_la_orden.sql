-- Operar a precio de mercado (HU-07, RF-26). El precio de referencia del instrumento cuando se creó la orden: el mismo
-- con el que se validó el precio y se reservó. Viaja en el comando NUEVA, que sale después, cuando la reserva se
-- acepta, y el motor ejecuta una orden a mercado solo dentro de ±10 % de él.
-- Las órdenes de antes quedan en NULL: si alguna a mercado sigue PENDIENTE, el motor la cancela sin ejecutarla.
ALTER TABLE orden ADD COLUMN precio_referencia BIGINT CHECK (precio_referencia > 0);
