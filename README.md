# BolsaSim — proyecto inicial

Simulador de bolsa: **auth-service** registra a los usuarios; **order-service** recibe sus órdenes por REST y las publica en Kafka; **matching-engine** las lee y las cruza en el libro de órdenes de su símbolo. Por ahora las ejecuciones solo se muestran en el log.

Piensa en Kafka como un buzón con 6 casilleros (particiones). order-service deja cada orden en el casillero de su símbolo y el motor las recoge en el mismo orden en que llegaron.

```
Postman / curl ──POST /api/usuarios──▶ auth-service ──▶ PostgreSQL (base "auth")
               │                       (puerto 8082) ──▶ Kafka: users.registered (clave = id del usuario)
               │  usuarioId = id del usuario registrado
               └─POST /api/ordenes───▶ order-service ──▶ Kafka: orders.commands ──▶ matching-engine
                                       (puerto 8081)      (6 particiones,            (log: "Orden recibida")
                                                           clave = símbolo)
```

## Estructura

| Carpeta | Qué contiene |
| --- | --- |
| `eventos/` | Java puro, sin Spring: los eventos `ComandoOrden` y `UsuarioRegistrado`, los enums y los nombres de tópicos. Lo comparten todos los servicios. |
| `order-service/` | API REST `POST /api/ordenes`, validaciones y el publicador de Kafka. Escucha `users.registered` y guarda los ids en su base (tabla `usuario_registrado`) para aceptar solo órdenes de usuarios registrados. |
| `matching-engine/` | El consumidor de Kafka y el libro de órdenes (`libro/`, Java puro con pruebas JUnit). |
| `auth-service/` | API REST `POST /api/usuarios` (registro, HU-01) y `GET /api/usuarios/{id}`. Guarda la tabla USUARIO en PostgreSQL con Flyway; contraseñas con BCrypt. Publica `UsuarioRegistrado` en `users.registered`. |
| `docker-compose.yml` | Kafka (modo KRaft), creación de tópicos, Kafka UI y PostgreSQL. |
| `postgres/init.sql` | Crea una base por servicio la primera vez que arranca PostgreSQL. |
| `peticiones.http` | Órdenes de ejemplo listas para enviar desde IntelliJ o la extensión REST Client de VS Code. |
| `enviar-ordenes.ps1` | Envía varias órdenes de una vez desde PowerShell (ver el libro de órdenes abajo). |

Versiones: Java 21, Spring Boot 4.1.1 (incluye Spring Kafka 4.1.1), Apache Kafka 4.2.1, PostgreSQL 17.

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

Debe terminar con `BUILD SUCCESS` y 49 pruebas pasadas: 14 de order-service, 14 del libro de órdenes y 21 de auth-service. Las de integración de auth-service y order-service usan Docker (Testcontainers con PostgreSQL y Kafka); con Docker cerrado se saltan.

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

En IntelliJ también puedes dar clic en el ▶ junto a `main` en `MatchingEngineApplication`, `OrderServiceApplication` y `AuthServiceApplication`.

**6. Registra un usuario.** Guarda el `id` de la respuesta: es el `usuarioId` de sus órdenes.

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/usuarios -ContentType "application/json" `
  -Body '{"nombre":"Cris","alias":"cris","correo":"cris@correo.com","contrasena":"secreta123","aceptaPoliticaDatos":true}'
```

Responde `201 Created` con el usuario (sin la contraseña) y publica el evento en `users.registered`: míralo en Kafka UI. Si el correo ya existe: `409 Conflict` con "Ese correo ya tiene una cuenta.", y no se publica nada.

**7. Envía una orden.** Solo los usuarios registrados pueden enviar órdenes: usa el `id` del paso 6. La forma más fácil es abrir `peticiones.http`, pegar el `id` en `@usuarioId` y presionar ▶. O desde la terminal (cambia `ID-DE-TU-USUARIO`):

Git Bash, macOS o Linux:

```bash
curl -X POST http://localhost:8081/api/ordenes \
  -H "Content-Type: application/json" \
  -d '{"usuarioId":"ID-DE-TU-USUARIO","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

PowerShell de Windows:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/ordenes -ContentType "application/json" `
  -Body '{"usuarioId":"ID-DE-TU-USUARIO","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

## Qué debes ver

- **Respuesta:** `202 Accepted` con el `ordenId`. Es 202 y no 201 porque la orden se procesa después, cuando el motor la lea.
- **Si el usuario no está registrado:** `422 Unprocessable Content` con el motivo, y la orden no se publica. order-service conoce a los usuarios por el tópico `users.registered`, así que justo después de registrarte puede tardar unos milisegundos en aceptarte.
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
| `422` con "no está registrado" | El `usuarioId` no es el `id` de un usuario registrado | Registra uno con `POST /api/usuarios` y usa su `id` |

## El libro de órdenes

Cada símbolo tiene su libro con las compras y ventas que esperan contraparte. Reglas (prioridad precio-tiempo):

- La compra que más paga y la venta que menos pide se atienden primero.
- A igual precio, se atiende primero la orden que llegó antes.
- El precio de la ejecución es el de la orden que ya estaba esperando.
- LIMITE: lo que no se ejecuta queda en el libro. MERCADO: lo que sobra se descarta.

Pruébalo con los servicios corriendo y el `id` de un usuario registrado:

```powershell
$yo = "ID-DE-TU-USUARIO"
.\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX -Lado VENTA -Precio 5000 -Cantidad 100 -Veces 1
.\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX -Lado COMPRA -Precio 5100 -Cantidad 150 -Veces 1
```

En el log de matching-engine verás `Ejecución: 100 acciones de ANDX a 5000 COP ...` y después `Libro ANDX | mejor compra 5100 | mejor venta -`: las 50 acciones que faltaron quedaron esperando.

Los libros viven en memoria: si reinicias matching-engine empiezan vacíos.

## Prueba de integración

Comprueba todo el camino de una vez: levanta Kafka y PostgreSQL, arranca los tres servicios, registra un usuario, comprueba que su evento llegue a `users.registered` y a order-service, que una orden de un usuario desconocido reciba 422, envía con su id una venta y una compra que se cruzan y revisa que el motor registre la ejecución a nombre de ese usuario. Detén tus servicios antes (usa los puertos 8081 y 8082) y ten Docker abierto:

```bash
./mvnw -B verify
bash scripts/prueba-integracion.sh
```

Debe terminar con `OK: el usuario se registró, sus órdenes viajaron por Kafka y el motor las ejecutó`. Los logs quedan en `target/integracion/`.

GitHub Actions (`.github/workflows/build.yml`) corre las pruebas y esta prueba de integración en cada push y pull request a `main`.

## Siguientes pasos

- **Outbox:** si Kafka está caído justo después de registrar a alguien, hoy el evento se pierde. La tabla OUTBOX del modelo entidad-relación guarda el evento junto con el usuario y lo publica después.
- **Login (HU-02):** token JWT; el `usuarioId` saldrá del token y no del cuerpo de la orden.
- **Ejecuciones en Kafka:** publicarlas en `trades.executed` para que el portafolio de cada usuario se entere de sus compras y ventas.
