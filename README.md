# BolsaSim — proyecto inicial

Simulador de bolsa: el **gateway** es la única entrada y exige un token válido; **auth-service** registra a los usuarios y les abre sesión con tokens JWT; **market-data-service** guarda el catálogo de instrumentos, que el administrador crea, edita y suspende; **order-service** recibe las órdenes por REST, acepta solo las de instrumentos activos con un precio válido (múltiplo del tick y a ±10 % del último precio), las guarda y le pide a **portfolio-service** que reserve su dinero (la saga `orders.requested` → `reservations.events`, con el patrón outbox en los dos lados); con la reserva aceptada envía la orden al motor, y sin saldo la deja `RECHAZADA` con el motivo. **matching-engine** la cruza en el libro de órdenes de su símbolo y publica lo que pasó: cada cambio de estado de una orden en `orders.events` y cada operación en `trades.executed`. order-service lee `orders.events` y actualiza cada orden con su estado, cantidad ejecutada y precio promedio, y `trades.executed` para el último precio. portfolio-service abre la cuenta de cada usuario con su saldo inicial, liquida cada compra (paga con la reserva, le suma las acciones y libera lo que sobró) y le muestra su saldo y sus movimientos de dinero.

Piensa en Kafka como un buzón con 6 casilleros (particiones). order-service deja cada orden en el casillero de su símbolo y el motor las recoge en el mismo orden en que llegaron.

```
                         ┌─/api/usuarios, /api/auth──▶ auth-service ──▶ PostgreSQL (base "auth")
Postman / curl ──▶ gateway                            (puerto 8082) ──▶ Kafka: users.registered
               (puerto 8080)                                             (clave = id del usuario, sin vencimiento)
       valida el JWT (RNF-22)                                                   │ abre la cuenta con 10.000.000 COP
                         ├─/api/cuenta──────────────▶ portfolio-service ◀───────┘
                         │                            (puerto 8084) ──▶ PostgreSQL (base "portafolio")
                         ├─/api/instrumentos────────▶ market-data-service ──▶ PostgreSQL (base "mercado")
                         │                            (puerto 8083)       ──▶ Kafka: instruments.events
                         │                                                    (compactado, clave = símbolo)
                         │                                                          │ copia local de los instrumentos
                         │                                                          ▼
                         └─/api/ordenes─────────────▶ order-service ──▶ PostgreSQL (base "ordenes"):
                           (con el mismo header        (puerto 8081)     ORDEN + OUTBOX en la misma transacción
                            Authorization)                                     │ publicador del outbox (cada 100 ms)
                                                                               ▼
                                         Kafka: orders.requested ──▶ portfolio-service reserva el dinero
                                         (clave = usuario)           (RESERVA + OUTBOX en la misma transacción)
                                                                               │
                                         order-service ◀── Kafka: reservations.events (aceptada o rechazada:
                                               │                                sin saldo, la orden queda RECHAZADA)
                                               ▼ con la reserva aceptada
                                         Kafka: orders.commands ──▶ matching-engine
                                         (6 particiones,            (libro en memoria, transacciones
                                          clave = símbolo)           de Kafka: comando y eventos juntos)
                                                                          │
              order-service actualiza la orden y ◀── Kafka: orders.events ◀┤
              portfolio-service liquida la compra    (estado de cada orden) │
                                                                           ▼
                                                    Kafka: trades.executed (cada operación; order-service
                                                    guarda el último precio)
```

## Estructura

| Carpeta | Qué contiene |
| --- | --- |
| `eventos/` | Java puro, sin Spring: los eventos `ComandoOrden`, `SolicitudReserva`, `ReservaProcesada`, `UsuarioRegistrado`, `InstrumentoActualizado`, `EventoOrden` y `OperacionEjecutada`, los enums (como `EstadoOrden`) y los nombres de tópicos. Lo comparten todos los servicios. |
| `order-service/` | API REST `POST /api/ordenes` y la consulta de tus órdenes (`GET /api/ordenes`, paginada por cursor, y `GET /api/ordenes/{id}`, HU-09). Rechaza con `422` un precio límite que no es múltiplo del tick o está a más de 10 % del último precio (RF-16). Guarda cada orden en la tabla ORDEN y su solicitud de reserva en OUTBOX en la misma transacción; un publicador la envía a `orders.requested` cada 100 ms, así nada se pierde si Kafka está caído (RNF-12). Cuando `reservations.events` trae la reserva aceptada, deja el comando de la orden en el outbox para `orders.commands`; si la trae rechazada, la orden queda `RECHAZADA` con el motivo (RF-18). Con el header `Idempotency-Key`, una orden enviada dos veces se crea una sola vez (HU-10). Vuelve a validar el JWT con la clave pública de auth-service y toma el usuario de la orden del token (`sub`), no del cuerpo. Escucha `users.registered` y `instruments.events` y guarda en su base una copia de los usuarios (tabla `usuario_registrado`) y de los instrumentos (tabla `instrumento`): acepta solo órdenes de usuarios registrados y de instrumentos activos. Escucha `orders.events` y actualiza cada orden; guarda cada ejecución en la tabla `ejecucion` y cada evento aplicado en `evento_procesado`, así un evento repetido no se aplica dos veces (RNF-09). Escucha `trades.executed` y guarda el último precio de cada instrumento, la referencia de la banda. |
| `market-data-service/` | Por ahora, solo el catálogo de instrumentos (HU-22): `GET /api/instrumentos` para cualquier usuario con token, y crear, editar, suspender y reactivar solo para el rol `ADMIN`. Guarda la tabla INSTRUMENTO en PostgreSQL con Flyway, arranca con 10 instrumentos ficticios y publica cada cambio en `instruments.events`. Valida el JWT por su cuenta. El último precio y las velas llegarán en el sprint 4. |
| `portfolio-service/` | La cuenta de cada usuario (HU-12): `GET /api/cuenta` (saldo disponible, reservado y total) y `GET /api/cuenta/movimientos` (filtrado por tipo y por fechas, paginado por cursor). Abre la cuenta con el saldo inicial al llegar `users.registered`, o la primera vez que el usuario la consulta si su evento no llegó. Escucha `orders.requested` y reserva el dinero de cada compra (RF-17): responde en `reservations.events`, con su propio outbox. Escucha `orders.events` y liquida cada compra: la paga con su reserva, le suma las acciones a la posición y libera lo que sobró (RF-30, RF-31). Guarda las tablas CUENTA, MOVIMIENTO, RESERVA, POSICION y OUTBOX en PostgreSQL con Flyway; ningún saldo ni posición puede quedar negativo (CHECK en la base, RNF-07). Valida el JWT por su cuenta. La consulta del portafolio llega con HU-11. |
| `matching-engine/` | El libro de órdenes (`libro/`, Java puro con pruebas JUnit) y su consumidor de Kafka. Por cada comando publica `EventoOrden` en `orders.events` y `OperacionEjecutada` en `trades.executed`, dentro de una transacción de Kafka junto con el offset del comando (RNF-10). Reconoce un comando repetido y reenvía lo mismo sin tocar el libro (RNF-09). |
| `auth-service/` | API REST `POST /api/usuarios` (registro, HU-01) y `GET /api/usuarios/{id}`. Guarda la tabla USUARIO en PostgreSQL con Flyway; contraseñas con BCrypt. Publica `UsuarioRegistrado` en `users.registered`. Sesión (HU-02): `POST /api/auth/login`, `/refresh` y `/logout`, con JWT firmados con RS256 y la clave pública en `/.well-known/jwks.json`. Vuelve a validar el JWT en las rutas protegidas: cada usuario solo consulta su propio perfil y solo cierra sus propias sesiones. Al arrancar crea el primer `ADMIN` con las variables `BOLSASIM_ADMIN_CORREO` y `BOLSASIM_ADMIN_CONTRASENA`. |
| `gateway/` | Spring Cloud Gateway (WebFlux) en el puerto 8080: enruta a auth-service, order-service, market-data-service y portfolio-service y exige un JWT válido, verificado con la clave pública de auth-service, salvo para registrarse, iniciar sesión, renovar el token y leer la clave. Reenvía el token original sin cambiarlo. |
| `docker-compose.yml` | Kafka (modo KRaft), creación de tópicos, Kafka UI y PostgreSQL. |
| `postgres/init.sql` | Crea una base por servicio la primera vez que arranca PostgreSQL. |
| `peticiones.http` | Registro, login, órdenes e instrumentos de ejemplo, listos para enviar desde IntelliJ o la extensión REST Client de VS Code. |
| `enviar-ordenes.ps1` | Inicia sesión y envía varias órdenes de una vez desde PowerShell (ver el libro de órdenes abajo). |

Versiones: Java 21, Spring Boot 4.1.1 (incluye Spring Kafka 4.1.1), Spring Cloud 2025.1.3 (Gateway 5.0.3), Apache Kafka 4.2.1, PostgreSQL 17.

## Requisitos

- **Java 21**: verifica con `java -version`.
- **Docker Desktop** abierto.
- **Maven no hace falta instalarlo**: el proyecto trae el Maven Wrapper (`mvnw`), que descarga la versión correcta la primera vez.

En PowerShell los comandos son `.\mvnw.cmd ...`; en Git Bash, macOS o Linux, `./mvnw ...`.

## Cómo correrlo

**1. Levanta Kafka y PostgreSQL** (en la carpeta del proyecto):

```bash
docker compose up -d
```

Espera unos 30 segundos y revisa con `docker compose ps`: `bolsasim-kafka` y `bolsasim-postgres` deben decir *healthy*. Abre http://localhost:8090 para ver Kafka UI; en *Topics* deben aparecer `orders.requested`, `reservations.events`, `orders.commands`, `orders.events`, `trades.executed`, `users.registered` e `instruments.events`, cada uno con 6 particiones. Si tu Kafka ya estaba arriba, `docker compose up -d` vuelve a correr `kafka-init` y crea los que falten. `instruments.events` es compactado: Kafka conserva siempre el último mensaje de cada símbolo, así un servicio nuevo lee el catálogo completo desde el principio.

Si tu contenedor de PostgreSQL es de antes de market-data-service o de portfolio-service, no tiene las bases `mercado` o `portafolio` (`init.sql` solo corre la primera vez). Créalas sin borrar nada: `docker exec bolsasim-postgres createdb -U bolsasim mercado` (o `portafolio`).

`users.registered` no vence (`retention.ms=-1`): un registro es un hecho que cualquier servicio nuevo necesita leer desde el principio. Los demás tópicos guardan 7 días (RNF-15). Si tu Kafka ya estaba arriba, `docker compose up -d` le aplica el cambio.

**2. Compila todo y corre las pruebas:**

```bash
./mvnw clean install
```

Debe terminar con `BUILD SUCCESS` y 354 pruebas pasadas: 120 de order-service, 36 de matching-engine, 83 de auth-service, 42 de market-data-service, 52 de portfolio-service y 21 del gateway. Las de integración usan Docker (Testcontainers con PostgreSQL y Kafka); con Docker cerrado se saltan.

**3. Arranca el motor** (terminal 1):

```bash
./mvnw -pl matching-engine spring-boot:run
```

**4. Arranca order-service** (terminal 2):

```bash
./mvnw -pl order-service spring-boot:run
```

**5. Arranca auth-service** (terminal 3). Las dos variables crean el primer administrador, que es quien gestiona los instrumentos; elige tu propia contraseña (de 8 a 72 caracteres), nunca la guardes en el repositorio (RNF-26):

```powershell
$env:BOLSASIM_ADMIN_CORREO = "admin@bolsasim.co"
$env:BOLSASIM_ADMIN_CONTRASENA = "una-contraseña-larga"
.\mvnw.cmd -pl auth-service spring-boot:run
```

En Git Bash: `BOLSASIM_ADMIN_CORREO=admin@bolsasim.co BOLSASIM_ADMIN_CONTRASENA=una-contraseña-larga ./mvnw -pl auth-service spring-boot:run`. El log dice `Administrador admin creado`. Solo se crea si nadie usa ese correo: una cuenta que ya existe nunca se vuelve ADMIN, y la contraseña de un ADMIN ya creado no cambia aunque cambies la variable. Sin las variables, auth-service arranca igual, sin administrador (lo avisa en el log). El alias es `admin`; para usar otro, define `BOLSASIM_ADMIN_ALIAS`.

**6. Arranca market-data-service y portfolio-service** (terminales 4 y 5):

```bash
./mvnw -pl market-data-service spring-boot:run
./mvnw -pl portfolio-service spring-boot:run
```

market-data-service publica al arrancar los 10 instrumentos en `instruments.events`; en el log de order-service verás `Instrumento ANDX guardado en la copia local`. portfolio-service lee `users.registered` desde el principio y abre la cuenta de cada usuario: verás `Cuenta del usuario ... abierta con 10000000 COP`.

**7. Arranca el gateway** (terminal 6):

```bash
./mvnw -pl gateway spring-boot:run
```

En IntelliJ también puedes dar clic en el ▶ junto a `main` en `MatchingEngineApplication`, `OrderServiceApplication`, `AuthServiceApplication`, `MarketDataServiceApplication` y `GatewayApplication` (las variables del ADMIN van en *Run → Edit Configurations → Environment variables*).

Desde aquí, todas las peticiones van al **gateway, puerto 8080**. La forma más fácil es `peticiones.http`: trae los pasos en orden.

**8. Registra un usuario.**

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/usuarios -ContentType "application/json" `
  -Body '{"nombre":"Cris","alias":"cris","correo":"cris@correo.com","contrasena":"secreta123","aceptaPoliticaDatos":true}'
```

Responde `201 Created` con el usuario (sin la contraseña) y publica el evento en `users.registered`: míralo en Kafka UI. Si el correo ya existe: `409 Conflict` con "Ese correo ya tiene una cuenta.", y no se publica nada.

**9. Inicia sesión (HU-02).** Guarda la respuesta: trae el token de acceso que piden las demás rutas.

```powershell
$sesion = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login -ContentType "application/json" `
  -Body '{"correo":"cris@correo.com","contrasena":"secreta123"}'
$sesion.tokenAcceso      # JWT de 15 minutos: pégalo en https://jwt.io para ver lo que lleva
$auth = @{ Authorization = "Bearer $($sesion.tokenAcceso)" }
```

Para renovar el token de acceso y cerrar sesión:

```powershell
$cuerpo = @{ tokenRefresco = $sesion.tokenRefresco } | ConvertTo-Json
$nuevo = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/refresh -ContentType "application/json" -Body $cuerpo
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/logout -Headers $auth -ContentType "application/json" -Body $cuerpo
```

| Endpoint | ¿Pide token? | Responde |
| --- | --- | --- |
| `POST /api/usuarios` | No | `201` con el usuario registrado. |
| `POST /api/auth/login` | No | `200` con `tokenAcceso` (JWT de 15 min), `tokenRefresco` (7 días) y cuándo vence cada uno. Correo o contraseña equivocados: `401` "Correo o contraseña incorrectos", sin decir cuál falló. |
| `POST /api/auth/refresh` | No | `200` con un `tokenAcceso` nuevo. Si la sesión se cerró o venció: `401`. |
| `POST /api/auth/logout` | Sí | `204`. El token de refresco queda revocado si es tuyo; si es de otro usuario también responde `204`, pero no se revoca. Si el token de acceso ya venció, renuévalo primero. |
| `GET /api/usuarios/{id}` | Sí | `200` con tu perfil. El de otro usuario responde `404`, igual que un id que no existe, para no revelar quién está registrado (RNF-23). Un `ADMIN` puede consultar cualquiera. |
| `GET /.well-known/jwks.json` (o `/oauth2/jwks`) | No | La clave pública para verificar la firma de los tokens, con `Cache-Control: max-age=300, public`. |
| `GET /api/instrumentos` y `/api/instrumentos/{simbolo}` | Sí | `200` con el catálogo (también los suspendidos, con su estado) o un instrumento; `404` si el símbolo no existe. |
| `POST /api/instrumentos` | Sí, `ADMIN` | `201` con el instrumento, que nace `ACTIVO`: se pueden enviar órdenes de él sin reiniciar nada (RNF-20). `409` "Ese símbolo ya existe". `400` si el precio inicial no es múltiplo del tick. |
| `PUT /api/instrumentos/{simbolo}` | Sí, `ADMIN` | `200`. Solo cambian `nombre` y `sector`; si llega `tick`, `precioInicial`, `accionesEmitidas` o `simbolo`, responde `400` diciendo que no se puede cambiar. |
| `POST /api/instrumentos/{simbolo}/suspender` y `/reactivar` | Sí, `ADMIN` | `200`. Suspendido, el instrumento rechaza órdenes nuevas con `422` (RF-29); reactivado, vuelve a aceptarlas. |
| Las rutas de `ADMIN` con otro rol | Sí | `403` "Solo un administrador puede gestionar instrumentos.". No `404` como en los perfiles: el catálogo lo ve cualquiera, así que no hay nada que ocultar. |
| `POST /api/ordenes` | Sí | `202` con el `ordenId` y el header `Location: /api/ordenes/{id}`. La orden queda guardada PENDIENTE aunque Kafka esté caído. Pide el header `Idempotency-Key` (`400` sin él); ver *Órdenes duplicadas* abajo. |
| `GET /api/ordenes/{id}` | Sí | `200` con la orden: estado, cantidad ejecutada, precio promedio y fechas. La de otro usuario responde `404`, igual que una que no existe (RNF-23). |
| `GET /api/ordenes?estado=…&tamano=…&despues=…` | Sí | `200` con `ordenes` (de la más reciente a la más vieja) y `siguiente`. Ver *Tus órdenes* abajo. |
| `GET /api/cuenta` | Sí | `200` con tu saldo disponible, reservado y total, y el saldo inicial. Si tu cuenta todavía no existía, esta consulta la abre. Un `ADMIN` recibe `404`: no tiene cuenta de inversión. Ver *Tu cuenta* abajo. |
| `GET /api/cuenta/movimientos?tipo=…&desde=…&hasta=…&tamano=…&despues=…` | Sí | `200` con `movimientos` (del más reciente al más viejo) y `siguiente`. |
| Todas las rutas que piden token | Sí | Sin token, con el token vencido, con la firma alterada o de otro emisor: `401` del gateway, y la petición no llega al servicio. Cada servicio vuelve a validar el token: llamándolos directo (8081 a 8084) también responden `401`. |

El token de acceso lleva solo el id del usuario (`sub`) y su `rol`: nada de correo ni nombre, porque cualquiera que lo tenga puede leerlo. En la base solo se guarda el hash del token de refresco. El gateway no mira el `rol`: cada servicio decide qué puede hacer cada rol.

**Los instrumentos.** market-data-service arranca con 10 empresas ficticias:

| Símbolo | Empresa | Sector | Precio inicial | Tick | Acciones emitidas |
| --- | --- | --- | ---: | ---: | ---: |
| ANDX | Andex Energía | Energía | 5.000 | 10 | 1.000.000 |
| CAFE | Café Cordillera | Agro | 12.000 | 50 | 500.000 |
| BGUA | Banco Guadua | Financiero | 25.000 | 50 | 400.000 |
| TAYR | Tayrona Telecom | Telecomunicaciones | 3.500 | 5 | 2.000.000 |
| QUIM | Cementos Quimbaya | Construcción | 8.000 | 10 | 800.000 |
| FLOR | Flores de la Sabana | Agro | 1.500 | 5 | 3.000.000 |
| COLB | Aerolínea Colibrí | Transporte | 6.200 | 10 | 900.000 |
| ARRA | Minera Arrayán | Minería | 18.000 | 50 | 300.000 |
| SALV | Clínicas Salud Viva | Salud | 9.500 | 10 | 600.000 |
| PACF | Software Pacífico | Tecnología | 40.000 | 100 | 200.000 |

Para crear más, inicia sesión con el ADMIN del paso 5 y usa las peticiones 17 a 25 de `peticiones.http`. Una vez creado, solo cambian el nombre, el sector y el estado: el símbolo es la clave en Kafka, las acciones emitidas son constantes (RNF-08) y un tick nuevo dejaría en precios inválidos las órdenes que ya están en el libro. Cada cambio sale en `instruments.events` con el estado completo del instrumento, y order-service actualiza su copia al instante.

**La clave de firma.** auth-service firma con RS256: la clave privada solo la tiene él, y los demás verifican con la pública. En desarrollo no tienes que hacer nada: si la variable `AUTH_JWT_CLAVE_PRIVADA` no existe, genera un par temporal al arrancar (lo avisa en el log) y, al reiniciar, los tokens anteriores dejan de valer. En un servidor genera la clave una vez y pásala por esa variable, nunca en el repositorio (RNF-26). Debe ser de al menos 2048 bits: con una más corta, auth-service no arranca.

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out clave-jwt.pem
export AUTH_JWT_CLAVE_PRIVADA="$(cat clave-jwt.pem)"
```

**10. Envía una orden** con el token del paso 9. No lleva el id del usuario: order-service lo toma del token, así nadie puede poner órdenes a nombre de otro (RNF-23). Sí lleva el header `Idempotency-Key`, una clave nueva por cada orden (ver *Órdenes duplicadas* abajo):

```powershell
$clave = @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ordenes -Headers ($auth + $clave) -ContentType "application/json" `
  -Body '{"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

Git Bash, macOS o Linux (cambia `TU-TOKEN`, y la clave en cada orden nueva):

```bash
curl -X POST http://localhost:8080/api/ordenes \
  -H "Authorization: Bearer TU-TOKEN" \
  -H "Idempotency-Key: 3f1c2b9e-7d4a-4e61-9a0b-5c8d2e7f1a64" \
  -H "Content-Type: application/json" \
  -d '{"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

O con `enviar-ordenes.ps1`, que inicia sesión por ti: `.\enviar-ordenes.ps1 -Correo cris@correo.com -Contrasena secreta123`.

## Qué debes ver

- **Respuesta:** `202 Accepted` con el `ordenId` y el header `Location`. Es 202 y no 201 porque la orden quedó guardada, pero la reserva y el motor la procesan después.
- **Sin token o con el token vencido:** `401 Unauthorized` del gateway; la orden ni siquiera llega a order-service.
- **Sin `Idempotency-Key`:** `400` "Falta el header Idempotency-Key", y no se guarda nada.
- **La misma solicitud otra vez con la misma clave:** `202` con la misma orden y el header `Idempotent-Replayed: true`; no se crea otra orden ni otro comando.
- **Si el cuerpo trae `usuarioId`** (o cualquier campo que no existe): `400 Bad Request` con "usuarioId ya no se envía: el usuario de la orden sale del token de acceso.", y la orden no se publica.
- **Si el usuario del token todavía no está en order-service:** `422 Unprocessable Content` con el motivo, y la orden no se publica. order-service conoce a los usuarios por el tópico `users.registered`, así que justo después de registrarte puede tardar unos milisegundos en aceptarte.
- **Si el instrumento no existe o está suspendido:** `422` con "El instrumento NADA no existe." o "El instrumento ANDX está suspendido: no recibe órdenes nuevas.", y la orden no se publica.
- **Si el precio no es válido (RF-16):** `422` con "El precio 5.005 no es múltiplo del tick de ANDX (10 COP): usa, por ejemplo, 5.000 o 5.010." o "El precio 6.000 está a más de 10 % del último precio de ANDX (5.000 COP): debe estar entre 4.500 y 5.500.", y la orden no se guarda. El último precio es el de la última operación del instrumento; si todavía no hay ninguna, su precio inicial. Ver *Reservas* abajo.
- **Log de order-service:** `Evento ... del outbox publicado en orders.requested (clave <tu usuario>, ...)`, unos 100 ms después de la respuesta.
- **Log de portfolio-service:** `Orden ...: 500000 COP reservados`, y después `Evento ... del outbox publicado en reservations.events`.
- **Log de order-service:** `Reserva de la orden ... aceptada (500000): va al motor` y `Evento ... del outbox publicado en orders.commands (clave ANDX, partición 2, offset 0)`.
- **Log de matching-engine:** `Orden recibida: NUEVA COMPRA 100 acciones de ANDX a 5000 COP | partición 2, offset 0` y después `Libro ANDX | mejor compra 5000 | mejor venta - | 1 mensajes publicados`.
- **Log de order-service:** `Orden ... ahora está ABIERTA (0 de 100 ejecutadas, precio promedio null)`, cuando llega su evento de `orders.events`.
- **Kafka UI:** en *Topics → orders.requested* la solicitud de reserva, en `reservations.events` la respuesta, en `orders.commands` el comando en JSON, y en `orders.events` lo que pasó con la orden.

El número de partición puede ser otro; lo importante es lo que muestran los experimentos.

## Órdenes duplicadas

Un doble clic en Comprar, o una app que reintenta porque se cayó la red, enviaría la misma orden dos veces (HU-10). Para evitarlo, cada orden lleva el header `Idempotency-Key`: una clave que el cliente genera para cada orden que quiere crear (lo recomendado es un UUID) y repite tal cual si reenvía esa misma orden.

| Llega | Responde |
| --- | --- |
| Una clave nueva | `202` y se crea la orden, como siempre. |
| La misma clave con la misma orden | `202` con la orden original (mismo `ordenId` y `Location`) y el header `Idempotent-Replayed: true`. No se crea otra orden ni otro comando para el motor. Da lo mismo cuánto tiempo haya pasado o si el instrumento se suspendió después: es la misma orden. |
| La misma clave con otra orden | `422` "Esa Idempotency-Key ya se usó para otra orden". |
| Dos solicitudes con la misma clave al mismo tiempo | Las dos reciben `202` con la misma orden. La segunda espera unos milisegundos a que la primera termine. |
| Sin header, o una clave que no tiene de 16 a 64 letras, números, `-` o `_` | `400`. |

- **La clave es por usuario:** dos usuarios pueden usar la misma sin chocar, y nadie obtiene la orden de otro adivinando su clave.
- **Una orden rechazada no gasta la clave:** si responde `400` o `422`, no se guardó nada; corrígela y reenvíala con la misma clave.
- **Cómo se garantiza:** la clave se guarda en la orden, con un índice único `(usuario_id, clave_idempotencia)`. Antes de crear la orden se busca la clave; si dos solicitudes llegan a la vez, ninguna ve a la otra, y ahí decide el índice: PostgreSQL hace esperar a la segunda hasta que la primera confirma y entonces la rechaza, y order-service responde con la orden de la primera. Una prueba lo reproduce de forma exacta: deja la primera sin confirmar y comprueba que la segunda espera.

## Reservas

Antes de llegar al motor, cada orden reserva lo que necesita (HU-05, RF-17). Así nadie compra con dinero que ya comprometió en otra orden, y la orden que se ejecuta siempre tiene con qué pagar.

1. order-service guarda la orden `PENDIENTE` y publica su solicitud en `orders.requested`, con el usuario como clave: las solicitudes de un usuario se procesan una tras otra.
2. portfolio-service calcula la reserva: una compra límite reserva precio × cantidad; una a mercado, cantidad × último precio × 1,10. Pasa ese monto del disponible al reservado en un solo `UPDATE` que también revisa que alcance, así dos órdenes a la vez nunca gastan el mismo saldo.
3. Responde en `reservations.events`. Con la reserva **aceptada**, order-service envía la orden al motor. Con la reserva **rechazada**, la orden queda `RECHAZADA` con el motivo, por ejemplo "Saldo insuficiente: tienes 1.000.000 COP disponibles y la orden necesita 50.000.000 COP.", y nunca llega al motor.
4. Cuando el motor la ejecuta, portfolio-service paga cada ejecución con la reserva, suma las acciones a tu posición (con su costo promedio) y libera lo que se pagó por debajo del límite. Cuando la orden termina, libera lo que quede.

Ejemplo de HU-05: con una venta de 100 a 4.900 en el libro, una compra límite de 100 a 5.000 reserva 500.000, se ejecuta a 4.900 (el precio de la orden que esperaba), paga 490.000 y libera los 10.000 sobrantes. En tus movimientos aparecen `RESERVA` −500.000, `COMPRA` −490.000 y `LIBERACION` +10.000.

Por ahora las ventas se aceptan sin reservar acciones y su liquidación (el dinero para el vendedor, las acciones que salen) llega con HU-06: nadie tiene acciones todavía, porque el bot creador de mercado recibe las emitidas con HU-24.

## Tu cuenta

Cada usuario recibe una cuenta con **10.000.000 COP** (RF-07; configurable con la variable `SALDO_INICIAL`). Se abre cuando portfolio-service lee su `users.registered`, o la primera vez que el usuario la consulta, si su evento todavía no llega o ya no está en Kafka (por ejemplo, porque se registró antes de que existiera portfolio-service). De las dos formas queda igual: una sola cuenta por usuario y un solo movimiento `SALDO_INICIAL`, aunque el evento y la consulta lleguen a la vez. Un ADMIN no tiene cuenta, porque no opera; los bots sí.

```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/cuenta -Headers $auth
(Invoke-RestMethod -Uri "http://localhost:8080/api/cuenta/movimientos?tamano=5" -Headers $auth).movimientos | Format-Table tipo, monto, simbolo, cantidad, creadoEn
```

- **Saldo:** `saldoDisponible` es lo que puedes usar, `saldoReservado` lo bloqueado en órdenes abiertas (ver *Reservas*) y `saldoTotal` la suma.
- **Movimientos:** cada entrada o salida de dinero, con su monto (positivo entra, negativo sale), la acción y la cantidad si aplica, y la orden u operación de origen. `RESERVA` y `LIBERACION` mueven dinero entre el disponible y el reservado de una orden; `COMPRA` es lo que pagaste en una operación. Así, `SALDO_INICIAL` + `COMPRA` + `VENTA` es tu saldo total. `VENTA` llega con HU-06 y `REINICIO` con HU-25.
- **Filtros:** `tipo` (uno de los seis), `desde` y `hasta` (días `AAAA-MM-DD` en hora de Colombia, los dos incluidos completos). La paginación es la misma por cursor que la de las órdenes: `tamano` de 1 a 100, y `siguiente` va en `despues`.
- **Nadie ve la cuenta de otro:** las rutas no llevan id; la cuenta siempre es la del token.
- **Ningún saldo queda negativo:** además de las reglas del servicio, la base lo rechaza con un CHECK (RNF-07).

## Tus órdenes

Cada orden queda guardada con su estado, y se actualiza sola cuando portfolio-service responde su reserva (`reservations.events`) y cuando el motor publica lo que pasó con ella (`orders.events`):

| Estado | Qué significa | Cantidad ejecutada | Motivo |
| --- | --- | --- | --- |
| `PENDIENTE` | Guardada en order-service, esperando su reserva o el motor (unos milisegundos, o más si alguno está detenido) | 0 | — |
| `RECHAZADA` | No había saldo para reservar (RF-18); nunca llegó al motor | 0 | "Saldo insuficiente: tienes 1.000.000 COP disponibles y la orden necesita 50.000.000 COP." |
| `ABIERTA` | Esperando en el libro, sin ejecutar | 0 | — |
| `PARCIAL` | Esperando en el libro, con una parte ejecutada | parte | — |
| `EJECUTADA` | Ejecutada completa | toda | — |
| `CANCELADA` | Una orden a mercado a la que le faltó contraparte (RF-26), o retirada del libro | lo que alcanzó | "Se ejecutaron 60 de 100 acciones; el resto se canceló porque no había más órdenes de venta.", "No había órdenes de venta para ejecutarla." o "Cancelada por el usuario." |

El **precio promedio** es Σ(precio × cantidad) / Σ(cantidad) de todas sus ejecuciones, con 4 decimales: comprar 40 a 4.900 y 60 a 5.000 da 4.960. Una orden a mercado ejecutada a medias queda `CANCELADA` pero con su cantidad ejecutada y su precio promedio: lo que se compró, se compró.

```powershell
(Invoke-RestMethod -Uri "http://localhost:8080/api/ordenes?tamano=5" -Headers $auth).ordenes | Format-Table simbolo, lado, cantidad, cantidadEjecutada, precioPromedio, estado
```

- **Filtro:** `estado=ABIERTAS` trae las `ABIERTA` y `PARCIAL` (HU-09); también sirve un estado exacto (`PENDIENTE`, `EJECUTADA`, `CANCELADA`...). Sin `estado`, todas.
- **Páginas:** `tamano` va de 1 a 100 (20 si no lo envías). Si hay más órdenes, la respuesta trae `siguiente`: envíalo tal cual en `despues` para pedir la página siguiente, con el mismo filtro. Cuando `siguiente` es `null`, no hay más.
- **Por qué un cursor y no números de página:** con `?pagina=500`, PostgreSQL tendría que leer y descartar 10.000 órdenes, y contar el total recorrería todas en cada petición; un bot que envía una orden cada 200 ms acumula unas 430.000 al día. Con el cursor, cada página salta directo en un índice a donde quedó la anterior: la primera y la número 10.000 cuestan lo mismo, y una orden nueva no corre las páginas siguientes. Por eso tampoco hay total.
- **Índices:** uno para cada forma de la consulta (todas, un estado y las abiertas), con las columnas en el orden de la lista. Una prueba revisa con `EXPLAIN` que cada consulta usa el suyo sin ordenar en memoria, con 20.000 órdenes de un mismo usuario.

**Si Kafka está caído** al enviar la orden, igual responde `202` y la orden queda guardada: su comando espera en la tabla `outbox` y sale en cuanto Kafka vuelve (el publicador lo reintenta cada 100 ms, y avisa en el log). Pruébalo con `docker compose stop kafka`, envía una orden y luego `docker compose start kafka`.

**Nada se pierde ni se aplica dos veces entre el motor y order-service:**

- **El motor procesa cada comando dentro de una transacción de Kafka** (RNF-10): los eventos de `orders.events` y `trades.executed` y la confirmación del comando quedan juntos. Si el motor se cae a mitad de camino, nadie ve esos eventos (los consumidores leen con `read_committed`) y el comando se vuelve a entregar.
- **Un comando repetido no entra dos veces al libro.** Puede repetirse porque el outbox entrega al menos una vez, o porque se abortó una transacción. El motor recuerda los últimos 10.000 comandos y, si uno se repite, reenvía los mismos eventos con los mismos ids sin tocar el libro.
- **order-service aplica cada evento una sola vez** (RNF-09): guarda su id en `evento_procesado` en la misma transacción que el cambio de la orden, y un evento repetido se ignora.
- **Un evento imposible** (por ejemplo, uno que ejecutaría más acciones de las pedidas, o que cambia una orden ya terminada) no se aplica: queda un `ERROR` en el log de order-service y el consumo sigue con el siguiente.

## Dos experimentos para entender Kafka

1. **La clave decide la partición.** Envía 3 órdenes de ANDX y 3 de CAFE. Todas las de ANDX caen en la misma partición, y lo mismo pasa con CAFE. Por eso el motor nunca recibe las órdenes de un símbolo en desorden.
2. **Kafka guarda los mensajes.** Detén matching-engine (Ctrl + C), envía 3 órdenes y vuelve a arrancarlo. Las 3 aparecen en el log: esperaron en Kafka hasta que el motor volvió.

## Problemas comunes

| Síntoma | Causa probable | Solución |
| --- | --- | --- |
| `Connection to node -1 (localhost:9092) could not be established` | Kafka no está arriba | `docker compose ps`; espera a *healthy* |
| `Port 9092 is already in use` | Otro Kafka corriendo | Detén el otro o cambia el puerto en `docker-compose.yml` y en los `application.yml` |
| `Could not find artifact co.bolsasim:eventos` | Corriste un servicio sin compilar antes | Ejecuta `./mvnw clean install` en la raíz |
| `Failed to install artifact co.bolsasim:eventos` | Un servicio corriendo tiene el jar bloqueado (Windows) | Detén los servicios y vuelve a compilar |
| `release version 21 not supported` | Maven usa otro Java | Configura Java 21 en IntelliJ (*File → Project Structure → SDK*) |
| auth-service, order-service o market-data-service: `Connection to localhost:5433 refused` | PostgreSQL no está arriba | `docker compose up -d postgres` y espera a *healthy* |
| `database "ordenes" does not exist` (o `mercado`, o `portafolio`) | Tu contenedor de PostgreSQL es de antes de que existiera esa base (`init.sql` solo corre la primera vez) | `docker exec bolsasim-postgres createdb -U bolsasim ordenes` (o `mercado`, o `portafolio`) |
| `404` "Los administradores no tienen cuenta de inversión." | Consultaste `/api/cuenta` con el token del ADMIN | Usa el token de un inversionista |
| `422` con "no está registrado" | order-service todavía no recibió el evento de `users.registered` de ese usuario | Espera unos segundos; si sigue, revisa que Kafka esté arriba y el log de order-service |
| `422` con "El instrumento ... no existe" | El símbolo no está en el catálogo, o market-data-service no ha arrancado nunca (es quien publica los 10 iniciales) | Revisa `GET /api/instrumentos`; arranca market-data-service y espera `Instrumento ANDX guardado en la copia local` en el log de order-service |
| `422` con "está suspendido" | Un ADMIN suspendió el instrumento | Que un ADMIN lo reactive con `POST /api/instrumentos/{simbolo}/reactivar` |
| `403` con "Solo un administrador" | Tu token es de un INVERSIONISTA o un BOT | Inicia sesión con el ADMIN del paso 5 |
| No puedes iniciar sesión como ADMIN | auth-service arrancó sin las variables, o ese correo ya era de una cuenta normal (nunca se asciende) | Revisa el log de auth-service: dice si creó el administrador o por qué no; usa un correo que nadie haya registrado |
| auth-service no arranca: `BOLSASIM_ADMIN_CONTRASENA debe tener...` | La contraseña del ADMIN es muy corta o muy larga | Usa una de 8 a 72 caracteres |
| `404` al consultar un usuario que sí existe | Es el perfil de otro usuario: solo puedes consultar el tuyo | Usa el `id` del usuario con el que iniciaste sesión (el `sub` del token) |
| `404` "No hay ninguna orden tuya con el id..." | La orden es de otro usuario, o el id está mal copiado | Usa el `ordenId` que te devolvió `POST /api/ordenes`, con tu propio token |
| `400` con "Falta el header Idempotency-Key" | La orden no trae la clave de idempotencia (antes de HU-10 no hacía falta) | Envía `Idempotency-Key` con una clave nueva por orden, por ejemplo un UUID |
| `422` con "Esa Idempotency-Key ya se usó para otra orden" | Reusaste la clave de una orden anterior para una orden distinta | Genera una clave nueva para cada orden; la misma clave solo sirve para reenviar la misma orden |
| Envías una orden y no aparece una nueva, pero responde `202` | Reenviaste una clave que ya usaste con esa misma orden: recibes la original (mira el header `Idempotent-Replayed`) | Cambia la clave si de verdad quieres otra orden igual |
| `400` con "despues no es válido" | El cursor se cortó o se modificó al copiarlo | Copia el valor de `siguiente` completo, sin cambiarlo, o empieza de nuevo sin `despues` |
| Las órdenes no llegan al motor y el log de order-service dice "Kafka no confirmó" o "No se pudo enviar" | Kafka está caído | Las órdenes están a salvo en la tabla `outbox`: levanta Kafka (`docker compose up -d kafka`) y salen solas |
| Las órdenes se quedan en `PENDIENTE` | matching-engine no está corriendo, o tu Kafka es de antes de `orders.events` | Arranca matching-engine; si el tópico no existe, `docker compose up -d` lo crea. Las órdenes esperan en Kafka y se procesan al volver |
| Una orden quedó `ABIERTA` en order-service pero el motor no la cruza | Reiniciaste matching-engine: sus libros viven en memoria y empiezan vacíos | Es una limitación conocida hasta las instantáneas del libro (RNF-13, ver *Siguientes pasos*) |
| `ERROR` "Evento ... no se aplica" en el log de order-service | El motor publicó un evento que no tiene sentido para esa orden | La orden queda como estaba; el mensaje dice por qué. Es un error del motor, no del usuario |
| `400` con "usuarioId ya no se envía" | La orden trae el `usuarioId` en el cuerpo, como antes del token | Quítalo: el usuario sale del token |
| `401` con "Falta el token de acceso" | La petición no trae el header `Authorization: Bearer ...` | Inicia sesión (`POST /api/auth/login`) y envía el `tokenAcceso` |
| `401` con "no es válido o ya venció" | Pasaron los 15 minutos, o reiniciaste auth-service (en desarrollo genera claves nuevas al arrancar) | Renueva con `POST /api/auth/refresh` o inicia sesión de nuevo |

## El libro de órdenes

Cada símbolo tiene su libro con las compras y ventas que esperan contraparte. Reglas (prioridad precio-tiempo):

- La compra que más paga y la venta que menos pide se atienden primero.
- A igual precio, se atiende primero la orden que llegó antes.
- El precio de la ejecución es el de la orden que ya estaba esperando.
- LIMITE: lo que no se ejecuta queda en el libro. MERCADO: lo que sobra se cancela y nunca queda en el libro (RF-26).

Pruébalo con los servicios y el gateway corriendo y el correo y la contraseña de un usuario registrado:

```powershell
$yo = @{ Correo = "cris@correo.com"; Contrasena = "secreta123" }
.\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado VENTA -Precio 5000 -Cantidad 100 -Veces 1
.\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado COMPRA -Precio 5100 -Cantidad 150 -Veces 1
```

En el log de matching-engine verás `Ejecución: 100 acciones de ANDX a 5000 COP ...` y después `Libro ANDX | mejor compra 5100 | mejor venta - | 3 mensajes publicados` (la operación y un evento por cada orden): las 50 acciones que faltaron quedaron esperando. Con `GET /api/ordenes` lo ves desde order-service: la venta `EJECUTADA` (100 de 100 a 5000) y la compra `PARCIAL` (100 de 150, precio promedio 5000).

Los libros viven en memoria: si reinicias matching-engine empiezan vacíos.

## Prueba de integración

Comprueba todo el camino de una vez, siempre a través del gateway: levanta Kafka y PostgreSQL, arranca los cinco servicios y el gateway (auth-service con un ADMIN nuevo y una contraseña aleatoria), registra un usuario, comprueba que su evento llegue a `users.registered`, a order-service y a portfolio-service, inicia sesión, consulta su saldo de 10.000.000 y su movimiento `SALDO_INICIAL` (el ADMIN recibe 404), comprueba que una orden sin token o con el token alterado reciba 401 (también llamando directo a los servicios, sin el gateway) y una con `usuarioId` en el cuerpo reciba 400. Después el ADMIN crea un instrumento con un símbolo al azar, con todo corriendo (un inversionista recibe 403 y repetirlo, 409), lo suspende (las órdenes reciben 422) y lo reactiva; un precio fuera del tick o de la banda de ±10 % recibe 422. Con el token se envían una venta y una compra de ese instrumento que se cruzan: cada una pide su reserva a portfolio-service, y se revisa que sus comandos salgan del outbox y que el motor registre la ejecución a nombre del usuario del token. La compra se reenvía con la misma `Idempotency-Key` y se comprueba que a `orders.commands` llega un solo comando. Las dos órdenes quedan `EJECUTADA` con su precio promedio (lo publica el motor en `orders.events`), hay una sola operación de la compra en `trades.executed`, se recorren página por página con el cursor, y otro usuario recibe 404. Después comprueba los criterios de HU-05: con una venta de 100 a 4.900 del otro usuario en el libro, una compra de 100 a 5.000 se ejecuta a 4.900, el saldo queda en 9.460.000 sin nada reservado y aparecen los movimientos `RESERVA`, `COMPRA` y `LIBERACION` (10.000); una compra sin contraparte queda `ABIERTA` con 450.000 reservados, y una sin saldo suficiente queda `RECHAZADA` con "Saldo insuficiente: tienes 9.010.000 COP disponibles...". Al final renueva el token y comprueba que, después de cerrar sesión, el token de refresco ya no sirve. Detén tus servicios antes (usa los puertos 8080 a 8084) y ten Docker abierto:

```bash
./mvnw -B verify
bash scripts/prueba-integracion.sh
```

Debe terminar con `OK: todo pasó por el gateway; el usuario se registró, inició sesión, el ADMIN creó un instrumento sin reiniciar nada, las órdenes quedaron guardadas, viajaron por Kafka y el motor las ejecutó`. Los logs quedan en `target/integracion/`.

GitHub Actions (`.github/workflows/build.yml`) corre las pruebas y esta prueba de integración en cada push y pull request a `main`.

## Siguientes pasos

- **Instantáneas del libro (RNF-13):** los libros viven en memoria y se pierden al reiniciar el motor. Publicarlos en `orderbook.snapshots` (compactado) y reconstruirlos al arrancar desde la última instantánea y los comandos posteriores.
- **Cancelar una orden (HU-08):** el motor ya cancela y publica `CANCELADA`; falta el endpoint en order-service que envía el comando CANCELAR.
- **Ventas con reserva de acciones (HU-06, RF-17, RF-30):** la saga ya existe; falta que portfolio-service reserve las acciones de cada venta (hoy la acepta sin reservar) y liquide al vendedor: el dinero entra a su disponible y las acciones salen de su posición. Depende de que alguien tenga acciones (RF-59).
- **Ver mi portafolio (HU-11, RF-32):** la tabla POSICION ya se llena con cada compra; falta el endpoint con el valor actual y la ganancia o pérdida.
- **Banda de ±10 % de las órdenes a mercado (RF-26, HU-07):** hoy una orden a mercado se ejecuta contra lo que haya; falta limitarla al ±10 % del último precio. Una compra a mercado ya reserva el 10 % de más; si se ejecuta por encima, portfolio-service cobra la diferencia del disponible, y si no alcanza deja un ERROR en el log.
- **Outbox en auth-service y market-data-service:** order-service ya lo usa. En auth-service, si Kafka está caído justo después de registrar a alguien, el evento se pierde. Con los instrumentos el daño es menor, porque market-data-service vuelve a publicar todo el catálogo cada vez que arranca.
- **Acciones del bot creador de mercado (RF-59):** `instruments.events` ya lleva las acciones emitidas; falta que portfolio-service se las asigne al bot al crear el instrumento (con bot-service, HU-24).
- **Mercado abierto (RF-16, HU-23):** order-service valida el tick y la banda; falta rechazar órdenes con el mercado cerrado, que llega con `market.status`. Hasta entonces el mercado está siempre abierto.
- **Límite de órdenes (RNF-24):** 20 órdenes por segundo por usuario en el gateway; el exceso recibe 429.
