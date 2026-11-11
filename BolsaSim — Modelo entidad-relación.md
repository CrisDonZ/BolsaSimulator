# BolsaSim — Modelo entidad-relación

Oct 13, 2026 · @Cristian Delgado · actualizado el Oct 21, 2026 con lo implementado hasta HU-06 y la oferta inicial (sprint 3)

## Diagrama entidad-relación

&#91;embedded content: modelo entidad-relación · 19 entidades en 6 servicios\]

El modelo tiene 19 entidades de negocio repartidas en 6 servicios. Las llaves foráneas reales solo existen dentro de un servicio; las referencias a usuarios, instrumentos u órdenes de otro servicio se sincronizan por Kafka.

## Diccionario de datos

Tipos de PostgreSQL. Los montos en COP usan BIGINT porque no tienen decimales; los estados son VARCHAR con una restricción CHECK.

### auth-service

**USUARIO**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador del usuario |
| nombre | VARCHAR(100) | NOT NULL | Nombre completo |
| alias | VARCHAR(30) | UNIQUE, NOT NULL | Nombre público en el ranking |
| correo | VARCHAR(150) | UNIQUE, NOT NULL | Correo de acceso |
| password\_hash | VARCHAR(100) | NOT NULL | Contraseña cifrada con BCrypt |
| rol | VARCHAR(20) | CHECK: INVERSIONISTA, ADMIN, BOT | Rol del usuario |
| estado | VARCHAR(20) | CHECK: ACTIVO, BLOQUEADO | Si puede iniciar sesión |
| fecha\_autorizacion\_datos | TIMESTAMPTZ | NOT NULL | Cuándo aceptó la política de tratamiento de datos |
| creado\_en | TIMESTAMPTZ | NOT NULL | Fecha de registro |

**TOKEN\_REFRESCO**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| usuario\_id | UUID | FK → USUARIO | Dueño del token |
| token\_hash | VARCHAR(100) | UNIQUE | Hash del token; el token nunca se guarda |
| expira\_en | TIMESTAMPTZ | NOT NULL | Vencimiento (7 días) |
| revocado | BOOLEAN | DEFAULT false | true al cerrar sesión |

### portfolio-service

**CUENTA**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| usuario\_id | UUID | UNIQUE; referencia lógica a USUARIO | Una cuenta por usuario |
| saldo\_inicial | BIGINT | NOT NULL | Base para calcular la rentabilidad |
| saldo\_disponible | BIGINT | CHECK ≥ 0 | Dinero libre para operar |
| saldo\_reservado | BIGINT | CHECK ≥ 0 | Dinero bloqueado en órdenes abiertas |
| rol | VARCHAR(15) | CHECK: INVERSIONISTA, BOT; NULL hasta que llega users.registered | Rol del dueño |
| creador\_mercado | BOOLEAN | NOT NULL, DEFAULT false; único parcial donde es true | La cuenta que recibe las acciones emitidas (RF-59) |
| version | INTEGER | NOT NULL | Bloqueo optimista |
| actualizado\_en | TIMESTAMPTZ | NOT NULL | Último cambio |

**POSICION**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| cuenta\_id | UUID | FK → CUENTA; UNIQUE con simbolo | Dueño |
| simbolo | VARCHAR(10) | Referencia lógica a INSTRUMENTO | Acción que posee |
| cantidad | BIGINT | CHECK ≥ 0 | Acciones que tiene; BIGINT porque el creador de mercado recibe todas las emitidas |
| cantidad\_reservada | BIGINT | CHECK entre 0 y cantidad | Acciones bloqueadas en órdenes de venta |
| costo\_promedio | NUMERIC(18,4) | NOT NULL | Precio promedio de compra; vender no lo cambia |
| version | INTEGER | NOT NULL | Bloqueo optimista |

**EMISION**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| simbolo | VARCHAR(10) | PK | Cada símbolo se emite una sola vez (RF-59) |
| acciones\_emitidas · precio\_inicial | BIGINT | CHECK > 0 | Lo que recibe el creador de mercado y su costo promedio |
| estado | VARCHAR(10) | CHECK: PENDIENTE, APLICADA | PENDIENTE mientras no existe la cuenta del creador de mercado |
| cuenta\_id | UUID | FK → CUENTA; NULL si está PENDIENTE | Quién la recibió |
| creada\_en · aplicada\_en | TIMESTAMPTZ | aplicada\_en NULL si está PENDIENTE | Fechas |

**RESERVA**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| cuenta\_id | UUID | FK → CUENTA | Cuenta que reserva |
| orden\_id | UUID | UNIQUE; referencia lógica a ORDEN | Una reserva por orden |
| tipo | VARCHAR(10) | CHECK: DINERO, ACCIONES | Qué se reserva |
| simbolo | VARCHAR(10) | NULL si tipo = DINERO | Acción reservada |
| precio\_limite | BIGINT | NULL salvo en una compra LIMITE | Con él se libera, en cada ejecución, lo pagado por debajo del límite (HU-05) |
| monto\_reservado | BIGINT | CHECK > 0 | COP o número de acciones |
| monto\_consumido | BIGINT | CHECK ≥ 0 | Lo ya usado en operaciones |
| monto\_liberado | BIGINT | CHECK ≥ 0; consumido + liberado ≤ reservado | Lo ya devuelto al disponible; lo pendiente es reservado − consumido − liberado |
| estado | VARCHAR(10) | CHECK: ACTIVA, CONSUMIDA, LIBERADA; si no es ACTIVA, no queda nada pendiente | ACTIVA mientras la orden vive; CONSUMIDA si terminó con algo ejecutado; LIBERADA si terminó sin ejecutar nada |
| creada\_en · actualizada\_en | TIMESTAMPTZ | NOT NULL | Fechas |

**MOVIMIENTO**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| cuenta\_id | UUID | FK → CUENTA | Cuenta afectada |
| tipo | VARCHAR(15) | CHECK: SALDO\_INICIAL, RESERVA, LIBERACION, COMPRA, VENTA, EMISION, REINICIO | Origen del movimiento; EMISION tiene monto 0 |
| monto | BIGINT | NOT NULL | Positivo entra, negativo sale |
| simbolo | VARCHAR(10) | NULL | Acción, si aplica |
| cantidad | BIGINT | NULL | Acciones, si aplica |
| referencia\_id | UUID | NULL | Orden u operación de origen |
| creado\_en | TIMESTAMPTZ | NOT NULL | Fecha |

**VALOR\_DIARIO** · **LISTA\_SEGUIMIENTO** · **ULTIMO\_PRECIO**

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| VALOR\_DIARIO | cuenta\_id + fecha | UUID + DATE | PK compuesta; cuenta\_id FK → CUENTA | Un registro por día |
| VALOR\_DIARIO | valor\_total | BIGINT | NOT NULL | Saldo + acciones al cierre |
| LISTA\_SEGUIMIENTO | cuenta\_id + simbolo | UUID + VARCHAR(10) | PK compuesta; cuenta\_id FK → CUENTA | Instrumento seguido |
| LISTA\_SEGUIMIENTO | agregado\_en | TIMESTAMPTZ | NOT NULL | Fecha |
| ULTIMO\_PRECIO | simbolo | VARCHAR(10) | PK | Copia local alimentada por `trades.executed` |
| ULTIMO\_PRECIO | precio · actualizado\_en | BIGINT · TIMESTAMPTZ | NOT NULL; precio > 0 | Para valorar portafolios y ranking. actualizado\_en es el momento de la operación: una más vieja no reemplaza a la copia. Sin fila, se usa el precio\_inicial de EMISION |

### order-service

**ORDEN**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| usuario\_id | UUID | Referencia lógica a USUARIO | Quién envía la orden |
| simbolo | VARCHAR(10) | Referencia lógica a INSTRUMENTO | Acción |
| lado | VARCHAR(6) | CHECK: COMPRA, VENTA | Sentido |
| tipo | VARCHAR(7) | CHECK: MERCADO, LIMITE | Tipo de orden |
| precio\_limite | BIGINT | Obligatorio si LIMITE, NULL si MERCADO | Precio máximo de compra o mínimo de venta |
| cantidad | INTEGER | CHECK > 0 | Acciones pedidas |
| cantidad\_ejecutada | INTEGER | CHECK entre 0 y cantidad | Acciones ya ejecutadas |
| precio\_promedio | NUMERIC(18,4) | NULL | Σ(precio × cantidad) / Σ(cantidad) de sus ejecuciones, con 4 decimales; NULL sin ejecuciones |
| estado | VARCHAR(10) | CHECK: PENDIENTE, ABIERTA, PARCIAL, EJECUTADA, CANCELADA, RECHAZADA | Ciclo de vida |
| motivo | VARCHAR(200) | NULL | Razón del rechazo o la cancelación |
| clave\_idempotencia | VARCHAR(64) | UNIQUE con usuario\_id | Header Idempotency-Key: evita órdenes duplicadas (HU-10); NULL en las órdenes anteriores a HU-10 |
| creada\_en · actualizada\_en | TIMESTAMPTZ | NOT NULL | Fechas |
| version | INTEGER | NOT NULL | Bloqueo optimista |

**EJECUCION**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | Identificador |
| orden\_id | UUID | FK → ORDEN; UNIQUE con operacion\_id | Orden ejecutada |
| operacion\_id | UUID | Referencia lógica a OPERACION | Operación de origen |
| precio | BIGINT | CHECK > 0 | Precio de esta ejecución |
| cantidad | INTEGER | CHECK > 0 | Acciones de esta ejecución |
| ejecutada\_en | TIMESTAMPTZ | NOT NULL | Fecha |

**Copias locales** (no son entidades de negocio nuevas: son réplicas de datos de otro servicio, alimentadas por Kafka, para validar órdenes sin consultar a nadie)

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| USUARIO\_REGISTRADO | usuario\_id | UUID | PK | Copia de USUARIO, alimentada por `users.registered`; solo el id, sin datos personales |
| USUARIO\_REGISTRADO | registrado\_en | TIMESTAMPTZ | NOT NULL | Fecha del registro |
| INSTRUMENTO | simbolo | VARCHAR(10) | PK | Copia de INSTRUMENTO, alimentada por `instruments.events` |
| INSTRUMENTO | estado · tick | VARCHAR(10) · BIGINT | CHECK: ACTIVO, SUSPENDIDO · CHECK > 0 | Para rechazar órdenes de instrumentos que no existen o están suspendidos; el tick, para RF-16 |
| INSTRUMENTO | actualizado\_en | TIMESTAMPTZ | NOT NULL | Un evento más viejo que la copia se ignora |

### market-data-service

**INSTRUMENTO**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| simbolo | VARCHAR(10) | PK; mayúsculas, 3 a 5 letras | Código de la acción |
| nombre | VARCHAR(100) | NOT NULL | Empresa ficticia |
| sector | VARCHAR(50) | NOT NULL | Sector económico |
| precio\_inicial | BIGINT | CHECK > 0; múltiplo del tick | Precio de salida |
| tick | BIGINT | CHECK > 0 | Variación mínima de precio |
| acciones\_emitidas | BIGINT | CHECK > 0 | Total de acciones (oferta inicial) |
| estado | VARCHAR(10) | CHECK: ACTIVO, SUSPENDIDO | Si se puede negociar |
| creado\_en | TIMESTAMPTZ | NOT NULL | Fecha |
| actualizado\_en | TIMESTAMPTZ | NOT NULL | Último cambio; va en `instruments.events` para que las copias ignoren un evento atrasado |

**OPERACION**

| Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- |
| id | UUID | PK | El id de la operación en `trades.executed`: si el evento llega dos veces, se guarda una sola |
| simbolo | VARCHAR(10) | FK → INSTRUMENTO; UNIQUE con secuencia | Acción negociada |
| secuencia | BIGINT | NOT NULL | Orden de ejecución dentro del instrumento; la asigna market-data-service al guardar |
| precio | BIGINT | CHECK > 0 | Precio pactado |
| cantidad | INTEGER | CHECK > 0 | Acciones negociadas |
| orden\_compra\_id · orden\_venta\_id | UUID | Referencia lógica a ORDEN | Las dos órdenes que se cruzaron |
| ejecutada\_en | TIMESTAMPTZ | NOT NULL | Fecha |

**VELA** · **MERCADO**

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| VELA | simbolo + intervalo + inicio | VARCHAR(10) + VARCHAR(3) + TIMESTAMPTZ | PK compuesta; simbolo FK → INSTRUMENTO; intervalo CHECK: 1m, 5m | Una vela por periodo |
| VELA | apertura · maximo · minimo · cierre | BIGINT | NOT NULL | Precios del periodo |
| VELA | volumen | BIGINT | CHECK ≥ 0 | Acciones negociadas en el periodo |
| MERCADO | id | SMALLINT | PK; una sola fila | Estado global |
| MERCADO | abierto | BOOLEAN | NOT NULL | Si se aceptan órdenes |
| MERCADO | modo | VARCHAR(10) | CHECK: MANUAL, HORARIO, DEMO | Cómo se abre y cierra |
| MERCADO | hora\_apertura · hora\_cierre | TIME | NULL si modo ≠ HORARIO | Horario |

### notification-service

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| ALERTA\_PRECIO | id | UUID | PK | Identificador |
| ALERTA\_PRECIO | usuario\_id · simbolo | UUID · VARCHAR(10) | Referencias lógicas | Dueño y acción |
| ALERTA\_PRECIO | condicion | VARCHAR(7) | CHECK: SUBE\_A, BAJA\_A | Sentido de la alerta |
| ALERTA\_PRECIO | precio\_objetivo | BIGINT | CHECK > 0 | Precio que la dispara |
| ALERTA\_PRECIO | estado | VARCHAR(10) | CHECK: ACTIVA, DISPARADA, CANCELADA | Se dispara una sola vez |
| ALERTA\_PRECIO | creada\_en · disparada\_en | TIMESTAMPTZ | disparada\_en NULL hasta dispararse | Fechas |
| NOTIFICACION | id | UUID | PK | Identificador |
| NOTIFICACION | usuario\_id | UUID | Referencia lógica a USUARIO | Destinatario |
| NOTIFICACION | tipo | VARCHAR(10) | CHECK: ORDEN, ALERTA, SISTEMA | Origen |
| NOTIFICACION | titulo · mensaje | VARCHAR(100) · VARCHAR(500) | NOT NULL | Contenido |
| NOTIFICACION | leida | BOOLEAN | DEFAULT false | Estado de lectura |
| NOTIFICACION | creada\_en | TIMESTAMPTZ | NOT NULL | Fecha |

### bot-service

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| BOT | id | UUID | PK | Identificador |
| BOT | usuario\_id | UUID | UNIQUE; referencia lógica a USUARIO con rol BOT | Cuenta con la que opera |
| BOT | nombre | VARCHAR(50) | NOT NULL | Nombre visible en el panel |
| BOT | estrategia | VARCHAR(20) | CHECK: CREADOR\_MERCADO, RUIDO | Comportamiento |
| BOT | activo | BOOLEAN | DEFAULT false | Encendido o apagado |
| BOT | frecuencia\_ms | INTEGER | CHECK ≥ 200 | Cada cuánto envía órdenes |
| BOT | cantidad\_maxima | INTEGER | CHECK > 0 | Tope de acciones por orden |
| BOT | margen\_ticks | INTEGER | CHECK ≥ 1 | Separación entre su compra y su venta |
| BOT\_INSTRUMENTO | bot\_id + simbolo | UUID + VARCHAR(10) | PK compuesta; bot\_id FK → BOT | Instrumentos que opera |

### Tablas comunes (en cada servicio que usa Kafka)

| Tabla | Campo | Tipo | Restricción | Descripción |
| --- | --- | --- | --- | --- |
| OUTBOX | id | UUID | PK | Identificador del evento |
| OUTBOX | topico · clave · tipo\_evento | VARCHAR(100) | NOT NULL | Destino en Kafka |
| OUTBOX | payload | JSONB | NOT NULL | Contenido del evento |
| OUTBOX | creado\_en · publicado\_en | TIMESTAMPTZ | publicado\_en NULL hasta enviarse | Control de envío |
| EVENTO\_PROCESADO | id\_evento | UUID | PK | Evento ya consumido |
| EVENTO\_PROCESADO | procesado\_en | TIMESTAMPTZ | NOT NULL | Fecha |

## Reglas de integridad y restricciones

La base de datos es la última barrera: aunque un servicio falle, estas reglas impiden que se cree o se pierda dinero.

1. **Una base por servicio.** Las referencias entre servicios no tienen llave foránea; se mantienen con eventos. Ejemplo: CUENTA.usuario\_id existe porque portfolio-service consumió `users.registered`.
2. **Montos enteros.** Dinero en BIGINT (COP sin decimales) para evitar errores de redondeo; solo los precios promedio usan NUMERIC(18,4).
3. **Saldos nunca negativos.** CHECK en saldo\_disponible, saldo\_reservado, cantidad y cantidad\_reservada.
4. **Reservas cuadradas.** La suma de las reservas ACTIVAS de dinero de una cuenta debe ser igual a su saldo\_reservado, y la de las reservas ACTIVAS de acciones de un símbolo, a la cantidad\_reservada de su posición; se verifica en la conciliación (RNF-08).
5. **Bloqueo optimista.** CUENTA, POSICION y ORDEN tienen columna version; si dos eventos cambian la misma fila a la vez, uno reintenta.
6. **Idempotencia.** Antes de procesar un evento se inserta su id en EVENTO\_PROCESADO, en la misma transacción; si ya existe, el evento se ignora.
7. **Outbox.** El cambio de negocio y la fila en OUTBOX se guardan juntos; un publicador envía la fila a Kafka y marca publicado\_en. Las filas salen en el orden en que se guardaron, y con varias instancias del servicio publica una sola a la vez (advisory lock de PostgreSQL). Entrega al menos una vez: quien consume reconoce el repetido (regla 6). Implementado en order-service y en portfolio-service (la respuesta de cada reserva).
8. **Órdenes coherentes.** precio\_limite es obligatorio si tipo = LIMITE y nulo si MERCADO; cantidad\_ejecutada nunca supera cantidad.
9. **Sin borrado de historia.** Órdenes, operaciones y movimientos no se borran, por auditoría. Si un usuario pide suprimir sus datos, se anonimizan nombre, alias y correo.
10. **Doble partida (RNF-08).** Cada operación se liquida a los dos lados: lo que paga el comprador lo cobra el vendedor, y las acciones que salen de uno entran al otro. La suma de saldo\_disponible + saldo\_reservado de todas las cuentas no cambia con las operaciones, y la suma de posicion.cantidad de cada símbolo es igual a sus acciones emitidas.

**Índices principales:**

Las listas que un usuario recorre (órdenes y movimientos) se paginan por cursor (keyset), nunca con OFFSET: cada página salta en el índice al punto donde quedó la anterior, así cuesta lo mismo la primera que la número 10.000. Por eso sus índices terminan en (creado DESC, id DESC), el mismo orden de la lista. Una prueba con EXPLAIN revisa en cada servicio que la consulta usa su índice sin ordenar en memoria.

| Tabla | Índice | Consulta que acelera |
| --- | --- | --- |
| ORDEN | (usuario\_id, creada\_en DESC, id DESC) | Lista de órdenes del usuario, paginada por cursor |
| ORDEN | (usuario\_id, estado, creada\_en DESC, id DESC) | Órdenes de un estado; reemplaza al (usuario\_id, estado) del diseño original y sirve para lo mismo |
| ORDEN | (usuario\_id, creada\_en DESC, id DESC) parcial donde estado IN (ABIERTA, PARCIAL) | Órdenes abiertas del usuario |
| ORDEN | UNIQUE (usuario\_id, clave\_idempotencia) | Reconocer la orden repetida, también si dos solicitudes llegan a la vez |
| EJECUCION | UNIQUE (orden\_id, operacion\_id) | Una operación se registra una sola vez por orden |
| OPERACION | (simbolo, ejecutada\_en DESC) | Últimas 20 operaciones; último precio y precio de apertura del día |
| MOVIMIENTO | (cuenta\_id, creado\_en DESC, id DESC) | Historial de la cuenta, paginado por cursor y filtrado por fechas |
| MOVIMIENTO | (cuenta\_id, tipo, creado\_en DESC, id DESC) | Historial filtrado por tipo |
| NOTIFICACION | (usuario\_id, leida) | Notificaciones sin leer |
| ALERTA\_PRECIO | (simbolo, estado) | Alertas activas a revisar con cada precio |
| OUTBOX | (creado\_en, id) parcial donde publicado\_en es NULL | Eventos pendientes de enviar, en el orden en que se guardaron |

## Datos fuera de la base relacional

El libro de órdenes vivo no está en PostgreSQL: vive en la memoria del motor, como la pizarra de un corredor de bolsa, y la base de datos es el archivo. Guardar cada cambio del libro en la base haría el cruce demasiado lento.

| Dato | Dónde vive | Por qué |
| --- | --- | --- |
| Libro de órdenes abierto | Memoria de matching-engine, ordenado por precio y hora | Cruzar órdenes en microsegundos |
| Instantánea del libro y offset del último comando | Tópico compactado `orderbook.snapshots` | Reconstruir el libro tras un reinicio (RNF-13) |
| Velas en construcción | Almacén de estado de Kafka Streams, respaldado en su tópico changelog | Agregar ventanas de 1 y 5 minutos en streaming |
| Historial de eventos | Tópicos de Kafka, 7 días; `users.registered` sin vencimiento | Reprocesar o auditar; un servicio nuevo lee todos los registros de usuarios |
| Sesiones WebSocket | Memoria de market-data-service y notification-service | No necesitan persistir |
