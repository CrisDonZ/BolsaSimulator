# BolsaSim — proyecto inicial

Primer paso del simulador de bolsa: **order-service** recibe una orden por REST y la publica en Kafka; **matching-engine** la lee y la cruza en el libro de órdenes de su símbolo. Por ahora las ejecuciones solo se muestran en el log.

Piensa en Kafka como un buzón con 6 casilleros (particiones). order-service deja cada orden en el casillero de su símbolo y el motor las recoge en el mismo orden en que llegaron.

```
Postman / curl ──POST──▶ order-service ──▶ Kafka: orders.commands ──▶ matching-engine
                         (puerto 8081)      (6 particiones,            (log: "Orden recibida")
                                             clave = símbolo)
```

## Estructura

| Carpeta | Qué contiene |
| --- | --- |
| `eventos/` | Java puro, sin Spring: el evento `ComandoOrden`, los enums y los nombres de tópicos. Lo comparten todos los servicios. |
| `order-service/` | API REST `POST /api/ordenes`, validaciones y el publicador de Kafka. Incluye pruebas unitarias. |
| `matching-engine/` | El consumidor de Kafka y el libro de órdenes (`libro/`, Java puro con pruebas JUnit). |
| `docker-compose.yml` | Kafka (modo KRaft), creación de tópicos y Kafka UI. |
| `peticiones.http` | Órdenes de ejemplo listas para enviar desde IntelliJ o la extensión REST Client de VS Code. |
| `enviar-ordenes.ps1` | Envía varias órdenes de una vez desde PowerShell (ver el libro de órdenes abajo). |

Versiones: Java 21, Spring Boot 4.1.1 (incluye Spring Kafka 4.1.1), Apache Kafka 4.2.1.

## Requisitos

- **Java 21**: verifica con `java -version`.
- **Docker Desktop** abierto.
- **IntelliJ IDEA** (ya trae Maven) o Maven 3.9+ instalado.

## Cómo correrlo

**1. Levanta Kafka** (en la carpeta del proyecto):

```bash
docker compose up -d
```

Espera unos 30 segundos y revisa con `docker compose ps`: `bolsasim-kafka` debe decir *healthy*. Abre http://localhost:8090 para ver Kafka UI; en *Topics* debe aparecer `orders.commands` con 6 particiones.

**2. Compila todo y corre las pruebas:**

```bash
mvn clean install
```

Debe terminar con `BUILD SUCCESS` y 18 pruebas pasadas (4 de order-service y 14 del libro de órdenes).

**3. Arranca el motor** (terminal 1):

```bash
mvn -pl matching-engine spring-boot:run
```

**4. Arranca order-service** (terminal 2):

```bash
mvn -pl order-service spring-boot:run
```

En IntelliJ también puedes dar clic en el ▶ junto a `main` en `MatchingEngineApplication` y en `OrderServiceApplication`.

**5. Envía una orden.** La forma más fácil es abrir `peticiones.http` en IntelliJ y presionar ▶. O desde la terminal:

Git Bash, macOS o Linux:

```bash
curl -X POST http://localhost:8081/api/ordenes \
  -H "Content-Type: application/json" \
  -d '{"usuarioId":"cris","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

PowerShell de Windows:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/ordenes -ContentType "application/json" `
  -Body '{"usuarioId":"cris","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}'
```

## Qué debes ver

- **Respuesta:** `202 Accepted` con el `ordenId`. Es 202 y no 201 porque la orden se procesa después, cuando el motor la lea.
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
| `Could not find artifact co.bolsasim:eventos` | Corriste un servicio sin compilar antes | Ejecuta `mvn clean install` en la raíz |
| `release version 21 not supported` | Maven usa otro Java | Configura Java 21 en IntelliJ (*File → Project Structure → SDK*) |

## El libro de órdenes

Cada símbolo tiene su libro con las compras y ventas que esperan contraparte. Reglas (prioridad precio-tiempo):

- La compra que más paga y la venta que menos pide se atienden primero.
- A igual precio, se atiende primero la orden que llegó antes.
- El precio de la ejecución es el de la orden que ya estaba esperando.
- LIMITE: lo que no se ejecuta queda en el libro. MERCADO: lo que sobra se descarta.

Pruébalo con los dos servicios corriendo:

```powershell
.\enviar-ordenes.ps1 -Simbolos ANDX -Lado VENTA -Precio 5000 -Cantidad 100 -Veces 1
.\enviar-ordenes.ps1 -Simbolos ANDX -Lado COMPRA -Precio 5100 -Cantidad 150 -Veces 1
```

En el log de matching-engine verás `Ejecución: 100 acciones de ANDX a 5000 COP ...` y después `Libro ANDX | mejor compra 5100 | mejor venta -`: las 50 acciones que faltaron quedaron esperando.

Los libros viven en memoria: si reinicias matching-engine empiezan vacíos.

## Siguiente paso

Publicar las ejecuciones en un tópico de Kafka (por ejemplo `trades`) para que otros servicios, como el portafolio de cada usuario, se enteren de las compras y ventas.
