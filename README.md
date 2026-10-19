# BolsaSim — proyecto inicial

Simulador de bolsa: el **gateway** es la única entrada y exige un token válido; **auth-service** registra a los usuarios y les abre sesión con tokens JWT; **order-service** recibe sus órdenes por REST y las publica en Kafka; **matching-engine** las lee y las cruza en el libro de órdenes de su símbolo. Por ahora las ejecuciones solo se muestran en el log.

Piensa en Kafka como un buzón con 6 casilleros (particiones). order-service deja cada orden en el casillero de su símbolo y el motor las recoge en el mismo orden en que llegaron.

```
                         ┌─/api/usuarios, /api/auth──▶ auth-service ──▶ PostgreSQL (base "auth")
Postman / curl ──▶ gateway                            (puerto 8082) ──▶ Kafka: users.registered
               (puerto 8080)                                             (clave = id del usuario)
       valida el JWT (RNF-22)
                         └─/api/ordenes─────────────▶ order-service ──▶ Kafka: orders.commands ──▶ matching-engine
                           (con el mismo header        (puerto 8081)     (6 particiones,            (log: "Orden recibida")
                            Authorization)                               clave = símbolo)
```

## Estructura

| Carpeta | Qué contiene |
| --- | --- |
| `eventos/` | Java puro, sin Spring: los eventos `ComandoOrden` y `UsuarioRegistrado`, los enums y los nombres de tópicos. Lo comparten todos los servicios. |
| `order-service/` | API REST `POST /api/ordenes`, validaciones y el publicador de Kafka. Vuelve a validar el JWT con la clave pública de auth-service y toma el usuario de la orden del token (`sub`), no del cuerpo. Escucha `users.registered` y guarda los ids en su base (tabla `usuario_registrado`) para aceptar solo órdenes de usuarios registrados. |
| `matching-engine/` | El consumidor de Kafka y el libro de órdenes (`libro/`, Java puro con pruebas JUnit). |
| `auth-service/` | API REST `POST /api/usuarios` (registro, HU-01) y `GET /api/usuarios/{id}`. Guarda la tabla USUARIO en PostgreSQL con Flyway; contraseñas con BCrypt. Publica `UsuarioRegistrado` en `users.registered`. Sesión (HU-02): `POST /api/auth/login`, `/refresh` y `/logout`, con JWT firmados con RS256 y la clave pública en `/.well-known/jwks.json`. |
| `gateway/` | Spring Cloud Gateway (WebFlux) en el puerto 8080: enruta a auth-service y order-service y exige un JWT válido, verificado con la clave pública de auth-service, salvo para registrarse, iniciar sesión, renovar el token y leer la clave. Reenvía el token original sin cambiarlo. |
| `docker-compose.yml` | Kafka (modo KRaft), creación de tópicos, Kafka UI y PostgreSQL. |
| `postgres/init.sql` | Crea una base por servicio la primera vez que arranca PostgreSQL. |
| `peticiones.http` | Registro, login y órdenes de ejemplo, listas para enviar desde IntelliJ o la extensión REST Client de VS Code. |
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

Espera unos 30 segundos y revisa con `docker compose ps`: `bolsasim-kafka` y `bolsasim-postgres` deben decir *healthy*. Abre http://localhost:8090 para ver Kafka UI; en *Topics* deben aparecer `orders.commands` y `users.registered`, cada uno con 6 particiones.

**2. Compila todo y corre las pruebas:**

```bash
./mvnw clean install
```

Debe terminar con `BUILD SUCCESS` y 113 pruebas pasadas: 23 de order-service, 14 del libro de órdenes, 60 de auth-service y 16 del gateway. Las de integración de auth-service y order-service usan Docker (Testcontainers con PostgreSQL y Kafka); con Docker cerrado se saltan.

**3. Arranca el motor** (terminal 1):

```bash
./mvnw -pl matching-engine spring-boot:run
```

**4. Arranca order-service** (terminal 2):

```bash
./mvnw -pl order-service spring-boot:run
```

**5. Arranca auth-service** (terminal 3):

```bash
./mvnw -pl auth-service spring-boot:run
```

**6. Arranca el gateway** (terminal 4):

```bash
./mvnw -pl gateway spring-boot:run
```

En IntelliJ también puedes dar clic en el ▶ junto a `main` en `MatchingEngineApplication`, `OrderServiceApplication`, `AuthServiceApplication` y `GatewayApplication`.

Desde aquí, todas las peticiones van al **gateway, puerto 8080**. La forma más fácil es `peticiones.http`: trae los pasos en orden.

**7. Registra un usuario.**

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/usuarios -ContentType "application/json" `
  -Body '{"nombre":"Cris","alias":"cris","correo":"cris@correo.com","contrasena":"secreta123","aceptaPoliticaDatos":true}'
```

Responde `201 Created` con el usuario (sin la contraseña) y publica el evento en `users.registered`: míralo en Kafka UI. Si el correo ya existe: `409 Conflict` con "Ese correo ya tiene una cuenta.", y no se publica nada.

**8. Inicia sesión (HU-02).** Guarda la respuesta: trae el token de acceso que piden las demás rutas.

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
| `POST /api/auth/logout` | Sí | `204`. El token de refresco queda revocado. Si el token de acceso ya venció, renuévalo primero. |
| `GET /.well-known/jwks.json` (o `/oauth2/jwks`) | No | La clave pública para verificar la firma de los tokens, con `Cache-Control: max-age=300, public`. |
| `GET /api/usuarios/{id}`, `/api/ordenes` y cualquier otra ruta | Sí | Sin token, con el token vencido, con la firma alterada o de otro emisor: `401` del gateway, y la petición no llega al servicio. order-service vuelve a validar el token: llamándolo directo al 8081 también responde `401`. |

El token de acceso lleva solo el id del usuario (`sub`) y su `rol`: nada de correo ni nombre, porque cualquiera que lo tenga puede leerlo. En la base solo se guarda el hash del token de refresco.

**La clave de firma.** auth-service firma con RS256: la clave privada solo la tiene él, y los demás verifican con la pública. En desarrollo no tienes que hacer nada: si la variable `AUTH_JWT_CLAVE_PRIVADA` no existe, genera un par temporal al arrancar (lo avisa en el log) y, al reiniciar, los tokens anteriores dejan de valer. En un servidor genera la clave una vez y pásala por esa variable, nunca en el repositorio (RNF-26). Debe ser de al menos 2048 bits: con una más corta, auth-service no arranca.

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out clave-jwt.pem
export AUTH_JWT_CLAVE_PRIVADA="$(cat clave-jwt.pem)"
```

**9. Envía una orden** con el token del paso 8. No lleva el id del usuario: order-service lo toma del token, así nadie puede poner órdenes a nombre de otro (RNF-23).

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ordenes -Headers $auth -ContentType "application/json" `
  -Body '{"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

Git Bash, macOS o Linux (cambia `TU-TOKEN`):

```bash
curl -X POST http://localhost:8080/api/ordenes \
  -H "Authorization: Bearer TU-TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

O con `enviar-ordenes.ps1`, que inicia sesión por ti: `.\enviar-ordenes.ps1 -Correo cris@correo.com -Contrasena secreta123`.

## Qué debes ver

- **Respuesta:** `202 Accepted` con el `ordenId`. Es 202 y no 201 porque la orden se procesa después, cuando el motor la lea.
- **Sin token o con el token vencido:** `401 Unauthorized` del gateway; la orden ni siquiera llega a order-service.
- **Si el cuerpo trae `usuarioId`** (o cualquier campo que no existe): `400 Bad Request` con "usuarioId ya no se envía: el usuario de la orden sale del token de acceso.", y la orden no se publica.
- **Si el usuario del token todavía no está en order-service:** `422 Unprocessable Content` con el motivo, y la orden no se publica. order-service conoce a los usuarios por el tópico `users.registered`, así que justo después de registrarte puede tardar unos milisegundos en aceptarte.
- **Log de order-service:** `Orden ... publicada en orders.commands (clave ANDX, partición 2, offset 0)`.
- **Log de matching-engine:** `Orden recibida: NUEVA COMPRA 100 acciones de ANDX a 5000 COP | partición 2, offset 0`.
- **Kafka UI:** en *Topics → orders.commands → Messages* aparece el mensaje en JSON.

El número de partición puede ser otro; lo importante es lo que muestran los experimentos.

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
| auth-service u order-service: `Connection to localhost:5433 refused` | PostgreSQL no está arriba | `docker compose up -d postgres` y espera a *healthy* |
| order-service: `database "ordenes" does not exist` | Tu contenedor de PostgreSQL es de antes de que existiera esa base (`init.sql` solo corre la primera vez) | `docker exec bolsasim-postgres createdb -U bolsasim ordenes` |
| `422` con "no está registrado" | order-service todavía no recibió el evento de `users.registered` de ese usuario | Espera unos segundos; si sigue, revisa que Kafka esté arriba y el log de order-service |
| `400` con "usuarioId ya no se envía" | La orden trae el `usuarioId` en el cuerpo, como antes del token | Quítalo: el usuario sale del token |
| `401` con "Falta el token de acceso" | La petición no trae el header `Authorization: Bearer ...` | Inicia sesión (`POST /api/auth/login`) y envía el `tokenAcceso` |
| `401` con "no es válido o ya venció" | Pasaron los 15 minutos, o reiniciaste auth-service (en desarrollo genera claves nuevas al arrancar) | Renueva con `POST /api/auth/refresh` o inicia sesión de nuevo |

## El libro de órdenes

Cada símbolo tiene su libro con las compras y ventas que esperan contraparte. Reglas (prioridad precio-tiempo):

- La compra que más paga y la venta que menos pide se atienden primero.
- A igual precio, se atiende primero la orden que llegó antes.
- El precio de la ejecución es el de la orden que ya estaba esperando.
- LIMITE: lo que no se ejecuta queda en el libro. MERCADO: lo que sobra se descarta.

Pruébalo con los servicios y el gateway corriendo y el correo y la contraseña de un usuario registrado:

```powershell
$yo = @{ Correo = "cris@correo.com"; Contrasena = "secreta123" }
.\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado VENTA -Precio 5000 -Cantidad 100 -Veces 1
.\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado COMPRA -Precio 5100 -Cantidad 150 -Veces 1
```

En el log de matching-engine verás `Ejecución: 100 acciones de ANDX a 5000 COP ...` y después `Libro ANDX | mejor compra 5100 | mejor venta -`: las 50 acciones que faltaron quedaron esperando.

Los libros viven en memoria: si reinicias matching-engine empiezan vacíos.

## Prueba de integración

Comprueba todo el camino de una vez, siempre a través del gateway: levanta Kafka y PostgreSQL, arranca los tres servicios y el gateway, registra un usuario, comprueba que su evento llegue a `users.registered` y a order-service, inicia sesión, comprueba que una orden sin token o con el token alterado reciba 401 (también llamando directo a order-service, sin el gateway) y una con `usuarioId` en el cuerpo reciba 400, envía con el token una venta y una compra que se cruzan y revisa que el motor registre la ejecución a nombre del usuario del token. Al final renueva el token y comprueba que, después de cerrar sesión, el token de refresco ya no sirve. Detén tus servicios antes (usa los puertos 8080, 8081 y 8082) y ten Docker abierto:

```bash
./mvnw -B verify
bash scripts/prueba-integracion.sh
```

Debe terminar con `OK: todo pasó por el gateway; el usuario se registró, inició sesión, sus órdenes viajaron por Kafka y el motor las ejecutó`. Los logs quedan en `target/integracion/`.

GitHub Actions (`.github/workflows/build.yml`) corre las pruebas y esta prueba de integración en cada push y pull request a `main`.

## Siguientes pasos

- **Outbox:** si Kafka está caído justo después de registrar a alguien, hoy el evento se pierde. La tabla OUTBOX del modelo entidad-relación guarda el evento junto con el usuario y lo publica después.
- **auth-service valida el JWT:** hoy `GET /api/usuarios/{id}` y `/api/auth/logout` solo están protegidos por el gateway; llamando directo al 8082 no piden token. Además, cada usuario debería poder consultar solo su propio perfil (RNF-23).
- **Límite de órdenes (RNF-24):** 20 órdenes por segundo por usuario en el gateway; el exceso recibe 429.
- **Ejecuciones en Kafka:** publicarlas en `trades.executed` para que el portafolio de cada usuario se entere de sus compras y ventas.
