# BolsaSim — Especificación del proyecto

Oct 15, 2026 · @Cristian Delgado

BolsaSim es un simulador web de bolsa de valores construido con Spring Boot y Apache Kafka. Este documento reúne en cuatro partes el alcance, los 59 requisitos funcionales, los 48 requisitos no funcionales y las 27 historias de usuario del proyecto.

## Parte 1 · Alcance

### Propósito y objetivos

BolsaSim es un simulador web de bolsa de valores. Los usuarios compran y venden acciones ficticias con dinero virtual, en un mercado que se mueve en tiempo real. Sirve para aprender cómo se forma un precio sin riesgo económico y como proyecto de portafolio en arquitectura orientada a eventos.

**Objetivo general:** construir con Spring Boot y Apache Kafka un mercado simulado que reciba órdenes, las cruce en un libro de órdenes y muestre los precios en vivo.

**Objetivos específicos:**

1. Permitir que cada usuario se registre y opere con un saldo virtual inicial.
2. Implementar un motor de emparejamiento con prioridad precio-tiempo y ejecuciones parciales.
3. Llevar precios, libro de órdenes y velas al navegador en menos de 1 segundo.
4. Calcular el valor de cada portafolio y un ranking de rentabilidad entre usuarios.
5. Generar liquidez con bots para que el mercado se mueva aunque haya pocos usuarios.
6. Levantar todo el sistema con un solo comando de Docker Compose.

### Descripción general

Una orden recorre seis pasos desde que el usuario la envía hasta que ve el precio actualizado en pantalla:

1. El usuario envía una orden de compra o venta, a precio de mercado o con precio límite.
2. El servicio de órdenes la guarda y publica en Kafka una solicitud de reserva de dinero o acciones.
3. El servicio de portafolio reserva los fondos; si no alcanzan, la orden se rechaza y se notifica.
4. Con la reserva lista, el motor de emparejamiento cruza la orden contra el libro de órdenes del instrumento.
5. Cada cruce genera una operación que el portafolio liquida: mueve dinero y acciones entre comprador y vendedor.
6. El servicio de datos de mercado calcula último precio y velas, y el navegador los recibe por WebSocket.

El libro de órdenes funciona como un tablero de clasificados: a un lado quién quiere comprar y a cuánto, al otro quién quiere vender. Cuando un precio de compra alcanza uno de venta, hay negocio y ese es el nuevo precio.

Los instrumentos son empresas ficticias que crea el administrador, cada una con precio inicial y variación mínima de precio (tick). Nadie fija el precio a mano: sale de las órdenes que se cruzan.

### Usuarios y roles

El sistema tiene cuatro roles; los bots usan el mismo flujo de órdenes que una persona, así el mercado nunca crea dinero de la nada.

| Rol | Quién es | Qué puede hacer |
| --- | --- | --- |
| Inversionista | Estudiante registrado | Operar, ver su portafolio, crear alertas de precio, aparecer en el ranking |
| Administrador | Docente o equipo del proyecto | Crear y suspender instrumentos, abrir o cerrar el mercado, configurar bots, reiniciar saldos |
| Bot de mercado | Cuenta de sistema | Enviar órdenes automáticas para dar liquidez; no aparece en el ranking |
| Visitante | Persona sin cuenta | Ver precios, gráficos y ranking en modo solo lectura |

### Dentro del alcance (versión 1)

La versión 1 incluye diez módulos, cada uno a cargo de un microservicio.

| Módulo | Incluye | Servicio |
| --- | --- | --- |
| Autenticación | Registro, login, roles, tokens JWT | auth-service |
| Cuenta virtual | Saldo inicial, saldo disponible y reservado, historial de movimientos | portfolio-service |
| Instrumentos | Catálogo de empresas ficticias, precio inicial, tick, estado | market-data-service |
| Órdenes | Compra y venta, tipos mercado y límite, cancelación, historial | order-service |
| Motor de emparejamiento | Libro por instrumento, prioridad precio-tiempo, ejecuciones parciales | matching-engine |
| Portafolio | Posiciones, precio promedio, ganancia o pérdida, valor total | portfolio-service |
| Datos de mercado | Último precio, libro a 5 niveles, velas de 1 y 5 minutos, en vivo | market-data-service |
| Ranking | Clasificación por rentabilidad sobre el saldo inicial | portfolio-service |
| Alertas y notificaciones | Alertas de precio, aviso de orden ejecutada o rechazada | notification-service |
| Bots y administración | Bots configurables, apertura y cierre del mercado, gestión de instrumentos | bot-service, varios |

### Fuera del alcance

Lo siguiente no se construye en la versión 1; los puntos marcados como fase 2 pueden entrar después.

- Dinero real, pasarelas de pago o retiros.
- Conexión con bolsas reales o datos de mercado reales.
- Venta en corto, apalancamiento, margen, derivados y opciones.
- Órdenes stop, stop-limit e iceberg (fase 2).
- Torneos con fechas de inscripción y premios (fase 2).
- Dividendos, divisiones de acciones y otros eventos corporativos.
- Comisiones por operación (fase 2, como parámetro configurable).
- App móvil nativa; la web será responsive.
- Recomendaciones de inversión o cualquier forma de asesoría financiera.

### Arquitectura de alto nivel

&#91;embedded content: arquitectura · 7 microservicios, gateway y Kafka\]

El navegador solo habla con el gateway. Detrás, los servicios se coordinan publicando y consumiendo eventos en Kafka, sin llamadas directas entre ellos; el motor y los bots no tienen API pública.

### Tópicos de Kafka

Doce tópicos conectan los servicios. La clave de partición decide qué mensajes se procesan en orden estricto.

| Tópico | Clave | Productor | Consumidores | Para qué |
| --- | --- | --- | --- | --- |
| `users.registered` (sin vencimiento) | usuarioId | auth-service | portfolio-service, order-service | Crear la cuenta con el saldo inicial; order-service guarda una copia de los usuarios para validar órdenes |
| `market.status` (compactado) | global | market-data-service | order-service, matching-engine, bot-service | Mercado abierto o cerrado |
| `instruments.events` (compactado) | símbolo | market-data-service | order-service, matching-engine, bot-service | Alta, cambio o suspensión de un instrumento |
| `orders.requested` | usuarioId | order-service | portfolio-service | Pedir la reserva de dinero o acciones |
| `reservations.events` | usuarioId | portfolio-service | order-service | Reserva aceptada o rechazada |
| `orders.commands` | símbolo | order-service | matching-engine | Comandos NUEVA y CANCELAR |
| `orders.events` | símbolo | matching-engine | order-service, portfolio-service, notification-service | Estado de la orden: en libro, parcial, ejecutada, cancelada |
| `trades.executed` | símbolo | matching-engine | order-service, market-data-service | Cada operación: precio, cantidad, orden de compra y de venta; order-service guarda el último precio (RF-16) |
| `orderbook.snapshots` (compactado) | símbolo | matching-engine | market-data-service, matching-engine | Libro a 5 niveles y punto de recuperación del motor |
| `marketdata.ticks` | símbolo | market-data-service | notification-service, bot-service | Último precio después de cada operación |
| `marketdata.candles` | símbolo | market-data-service (Kafka Streams) | market-data-service | Velas de 1 y 5 minutos para guardar y enviar al navegador |
| `portfolio.updated` | usuarioId | portfolio-service | notification-service | Cambio de saldo o posición para enviarlo al navegador |

**Reglas de diseño:**

- Clave = símbolo en todo lo que toca el libro: las órdenes de un instrumento caen en la misma partición y el motor las procesa en orden de llegada.
- Clave = usuarioId en las reservas: las solicitudes de un usuario se procesan una tras otra, así no gasta dos veces el mismo saldo.
- NUEVA y CANCELAR comparten tópico a propósito: en tópicos separados, una cancelación podría llegar antes que su orden.
- 6 particiones por tópico principal; replicación 1 en desarrollo y 3 en un entorno tipo producción.
- Cada tópico tiene un tópico de errores `<nombre>.DLT` para mensajes que fallan tras 3 reintentos.

### Stack tecnológico

| Capa | Tecnología |
| --- | --- |
| Lenguaje y framework | Java 21 (LTS), Spring Boot, Spring Web, Spring Data JPA |
| Mensajería | Apache Kafka en modo KRaft, Spring for Apache Kafka, Kafka Streams |
| Base de datos | PostgreSQL, una base por servicio; migraciones con Flyway |
| Seguridad | Spring Security, JWT, contraseñas con BCrypt |
| Entrada y tiempo real | Spring Cloud Gateway; WebSocket con STOMP |
| Frontend | React con TypeScript; gráfico de velas con una librería como Lightweight Charts |
| Documentación de API | springdoc-openapi (Swagger UI) |
| Pruebas | JUnit 5, Mockito, Testcontainers (Kafka y PostgreSQL), k6 para carga |
| Observabilidad | Spring Boot Actuator, Micrometer, Prometheus, Grafana |
| Despliegue | Docker, Docker Compose; integración continua con GitHub Actions |

### Supuestos y restricciones

**Supuestos** (ajustables):

- Equipo de 1 a 3 desarrolladores, con 12 semanas de trabajo en 6 sprints de 2 semanas.
- Moneda única: pesos colombianos virtuales (COP), sin decimales; saldo inicial por defecto de 10.000.000 COP.
- Se arranca con 10 instrumentos ficticios y 3 bots activos.
- Las acciones se negocian en cantidades enteras.
- Horario de mercado configurable; por defecto 8:00 a 16:00, hora de Colombia, con modo demo 24/7.

**Restricciones:**

- Todo debe correr en un solo computador con Docker Compose; se recomiendan 16 GB de RAM.
- Solo herramientas gratuitas o de código abierto.
- El sistema debe mostrar en todo momento que es una simulación y que no ofrece asesoría financiera.

### Entregables y fases

El proyecto se entrega en 6 sprints de 2 semanas; cada uno termina con algo que se puede demostrar.

1. **Base:** repositorio, Docker Compose con Kafka y PostgreSQL, auth-service, gateway e integración continua.
2. **Órdenes y reservas:** instrumentos, order-service y la saga de reserva con portfolio-service.
3. **Motor de emparejamiento:** libro precio-tiempo, ejecuciones parciales, cancelación y transacciones de Kafka.
4. **Liquidación y datos de mercado:** liquidación en portafolio, último precio y velas con Kafka Streams, WebSocket.
5. **Frontend y bots:** pantalla de operación con gráfico, libro y portafolio; bots de mercado.
6. **Cierre:** ranking, alertas, panel de administración, pruebas de carga y documentación final.

**Entregables finales:**

- Código fuente en GitHub con instrucciones de instalación.
- Archivo Docker Compose que levanta todo el sistema.
- Documentación: esta especificación, el modelo entidad-relación y la API en OpenAPI.
- Informe de pruebas de carga con latencia y capacidad medidas.
- Video de demostración de 5 minutos.

## Parte 2 · Requisitos funcionales

### Convenciones

BolsaSim tiene 59 requisitos funcionales en 11 módulos. Cada uno lleva un identificador RF-NN, un enunciado verificable y una prioridad MoSCoW:

- **Debe:** sin esto la versión 1 no funciona.
- **Debería:** importante; entra si el tiempo alcanza.
- **Podría:** deseable; candidato a la fase 2.

Los montos están en pesos colombianos virtuales (COP). El servicio responsable de cada módulo está en la parte 1.

### Usuarios y autenticación

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-01 | El sistema debe permitir el registro con nombre, correo y contraseña; el correo es único. | Debe |
| RF-02 | El inicio de sesión debe entregar un token JWT de 15 minutos y un token de refresco de 7 días. | Debe |
| RF-03 | El cierre de sesión debe invalidar el token de refresco. | Debe |
| RF-04 | El usuario debe poder recuperar su contraseña con un enlace de un solo uso enviado al correo. | Podría |
| RF-05 | El usuario debe poder editar su alias, que es el nombre que aparece en el ranking. | Debería |
| RF-06 | El sistema debe manejar los roles inversionista, administrador y bot, con permisos por rol. | Debe |

### Cuenta y saldo virtual

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-07 | Al registrarse un usuario, el sistema debe crear su cuenta con un saldo inicial configurable (por defecto 10.000.000 COP). | Debe |
| RF-08 | El usuario debe ver su saldo disponible, su saldo reservado en órdenes abiertas y su saldo total. | Debe |
| RF-09 | El sistema debe registrar cada movimiento (reserva, liberación, compra, venta, reinicio) con fecha y orden de origen. | Debe |
| RF-10 | El usuario debe poder consultar su historial de movimientos, filtrado por fecha y tipo. | Debería |

### Instrumentos

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-11 | El sistema debe listar los instrumentos con símbolo, nombre, sector, último precio y variación del día. | Debe |
| RF-12 | Cada instrumento debe tener una ficha con gráfico de velas, libro de órdenes y últimas operaciones. | Debe |
| RF-13 | El usuario debe poder buscar instrumentos por símbolo o nombre. | Debería |
| RF-14 | El usuario debe poder guardar instrumentos en una lista de seguimiento. | Podría |

### Órdenes

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-15 | El usuario debe poder crear una orden de compra o venta, tipo mercado o límite, con cantidad entera mayor que cero. | Debe |
| RF-16 | El sistema debe validar que el mercado esté abierto, el instrumento activo y el precio límite sea múltiplo del tick y esté a ±10 % del último precio. | Debe |
| RF-17 | El sistema debe reservar fondos antes de enviar la orden al motor: compra límite = precio × cantidad; compra a mercado = cantidad × último precio × 1,10; venta = las acciones. | Debe |
| RF-18 | El sistema debe rechazar la orden, con el motivo, si no hay saldo o acciones suficientes. | Debe |
| RF-19 | El usuario debe poder cancelar una orden abierta o parcialmente ejecutada; la reserva restante se libera. | Debe |
| RF-20 | El usuario debe ver sus órdenes abiertas y su historial con estado, cantidad ejecutada y precio promedio. | Debe |
| RF-21 | Las órdenes límite deben vencer al cierre del mercado y cancelarse automáticamente. | Debería |
| RF-22 | El sistema no debe duplicar una orden si el usuario reenvía la misma solicitud (clave de idempotencia). | Debe |

La reserva de compra a mercado lleva un 10 % extra porque el precio puede moverse entre el envío y la ejecución; lo que sobre se devuelve al terminar la orden.

&#91;embedded content: ciclo de vida de una orden · 6 estados, 3 finales\]

Una orden queda en Pendiente mientras el portafolio reserva los fondos. Desde Abierta o Parcial solo puede terminar ejecutada o cancelada.

### Motor de emparejamiento

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-23 | El motor debe mantener un libro por instrumento: compras de mayor a menor precio, ventas de menor a mayor y, a igual precio, primero la más antigua. | Debe |
| RF-24 | Una orden entrante debe cruzarse mientras su precio alcance el mejor precio contrario; la operación se hace al precio de la orden que ya estaba en el libro. | Debe |
| RF-25 | Lo no ejecutado de una orden límite debe quedar en el libro, conservando su prioridad. | Debe |
| RF-26 | Una orden a mercado debe ejecutarse solo dentro de ±10 % del último precio; lo que no se ejecute se cancela y nunca queda en el libro. | Debe |
| RF-27 | Una orden no debe cruzarse con otra del mismo usuario; en ese caso la orden entrante se cancela. | Debería |
| RF-28 | El motor debe publicar cada operación y cada cambio de estado de orden en Kafka. | Debe |
| RF-29 | Un instrumento suspendido debe rechazar órdenes nuevas, permitir cancelaciones y conservar su libro. | Debería |

Ejemplo: en el libro hay una venta de 100 acciones a 5.000 COP y llega una compra límite de 150 a 5.100. Se ejecutan 100 a 5.000 y las 50 restantes quedan en el libro como compra a 5.100.

### Portafolio

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-30 | El sistema debe liquidar cada operación: al comprador se le debita el dinero y se le acreditan las acciones; al vendedor, al contrario. | Debe |
| RF-31 | El sistema debe liberar la reserva sobrante cuando una orden termina: ejecutada, cancelada o rechazada. | Debe |
| RF-32 | El usuario debe ver cada posición con cantidad, precio promedio de compra, valor actual y ganancia o pérdida en COP y en %. | Debe |
| RF-33 | El usuario debe ver el valor total de su portafolio (saldo + valor de mercado de sus acciones) actualizado en vivo. | Debe |
| RF-34 | El usuario debe ver un gráfico con el valor de su portafolio al cierre de cada día. | Podría |

### Datos de mercado en tiempo real

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-35 | El sistema debe publicar el último precio de un instrumento después de cada operación. | Debe |
| RF-36 | La ficha debe mostrar en vivo el libro a 5 niveles por lado, con precio y cantidad agregada por nivel. | Debe |
| RF-37 | El sistema debe calcular velas de 1 y 5 minutos con apertura, máximo, mínimo, cierre y volumen. | Debe |
| RF-38 | Al abrir una ficha, el gráfico debe cargar el historial de velas y seguir actualizándose en vivo. | Debe |
| RF-39 | La ficha debe mostrar las últimas 20 operaciones con hora, precio y cantidad. | Debería |
| RF-40 | El sistema debe calcular la variación del día frente al precio de apertura. | Debería |

### Ranking

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-41 | El ranking debe ordenar a los inversionistas por rentabilidad = (valor total − saldo inicial) ÷ saldo inicial. | Debe |
| RF-42 | El ranking debe actualizarse al menos una vez por minuto y mostrar el top 50. | Debería |
| RF-43 | El ranking debe excluir bots y administradores. | Debe |
| RF-44 | Cada usuario debe ver su propia posición aunque no esté en el top 50. | Debería |

### Alertas y notificaciones

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-45 | El usuario debe poder crear una alerta de precio con instrumento, condición (sube a / baja a) y precio objetivo. | Debería |
| RF-46 | Una alerta debe dispararse una sola vez al cumplirse la condición y generar una notificación. | Debería |
| RF-47 | El usuario debe recibir en vivo un aviso cuando su orden se ejecuta, se ejecuta en parte, se cancela o se rechaza. | Debe |
| RF-48 | El usuario debe tener un centro de notificaciones donde marcarlas como leídas. | Debería |

### Bots de mercado

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-49 | El administrador debe poder crear un bot con instrumentos, estrategia, frecuencia y cantidad máxima por orden. | Debe |
| RF-50 | La estrategia creador de mercado debe mantener una compra y una venta alrededor del último precio, separadas por un margen configurable. | Debe |
| RF-51 | La estrategia de ruido debe enviar órdenes pequeñas al azar para que el precio se mueva. | Debería |
| RF-52 | El administrador debe poder encender o apagar cada bot desde el panel. | Debe |
| RF-53 | Los bots deben operar con cuenta y saldo propios y pasar por las mismas validaciones que un usuario. | Debe |

### Administración

| ID | Requisito | Prioridad |
| --- | --- | --- |
| RF-54 | El administrador debe poder crear, editar y suspender instrumentos (símbolo, nombre, sector, precio inicial, tick, acciones emitidas). | Debe |
| RF-55 | El administrador debe poder abrir y cerrar el mercado manualmente o programar el horario. | Debe |
| RF-56 | El administrador debe poder reiniciar saldos y posiciones de todos los inversionistas para empezar una nueva temporada. | Debería |
| RF-57 | El administrador debe poder bloquear y desbloquear usuarios. | Debería |
| RF-58 | El panel debe mostrar órdenes por minuto, operaciones del día y usuarios activos. | Podría |
| RF-59 | Al crear un instrumento, el sistema debe asignar todas sus acciones emitidas a la cuenta del bot creador de mercado (oferta inicial). | Debe |

## Parte 3 · Requisitos no funcionales

### Convenciones

BolsaSim tiene 48 requisitos no funcionales, cada uno con un criterio que se puede medir o comprobar. Se identifican como RNF-NN.

Las cifras de rendimiento se miden en el entorno de referencia: un computador con 16 GB de RAM que corre todo el sistema con Docker Compose. La carga se genera con k6 y la latencia se reporta en el percentil 95 (p95): el 95 % de las peticiones debe cumplir el valor.

### Rendimiento

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-01 | El motor publica la operación ≤ 50 ms (p95) después de que la orden entra a `orders.commands`. | Métrica de Micrometer con la marca de tiempo del evento |
| RNF-02 | Desde el clic en Comprar hasta ver la orden ejecutada en pantalla pasan ≤ 1 s (p95). | Prueba k6 con navegador simulado |
| RNF-03 | El sistema procesa 500 órdenes por segundo durante 10 minutos sin errores. | Prueba de carga k6; informe de errores |
| RNF-04 | Las consultas REST (portafolio, historial, ranking) responden en ≤ 300 ms (p95). | Prueba k6 con 200 usuarios simultáneos |
| RNF-05 | Precio y libro llegan al navegador ≤ 500 ms (p95) después de cada operación. | Marca de tiempo en el mensaje WebSocket |
| RNF-06 | El retraso (lag) de cada grupo de consumidores se mantiene bajo 1.000 mensajes con la carga de RNF-03. | Tablero de Grafana durante la prueba |

### Consistencia e integridad de datos

El requisito más importante del sistema: el dinero y las acciones nunca se crean ni se pierden por un error técnico.

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-07 | Ningún saldo disponible ni posición puede quedar negativo. | Restricción CHECK en la base de datos y prueba que lo intenta |
| RNF-08 | El dinero total del sistema y las acciones totales de cada instrumento son constantes; solo cambian por un reinicio del administrador. | Consulta de conciliación después de cada prueba de carga |
| RNF-09 | Procesar dos veces el mismo evento no cambia el resultado (idempotencia). | Cada consumidor guarda el id del evento; prueba que reenvía 1.000 duplicados |
| RNF-10 | El motor y Kafka Streams procesan cada mensaje exactamente una vez. | Transacciones de Kafka en el motor y `exactly_once_v2` en Streams |
| RNF-11 | Los comandos de un mismo instrumento se procesan en el orden en que llegaron. | Clave = símbolo; prueba con 10.000 órdenes de un solo símbolo |
| RNF-12 | Ningún evento se pierde si un servicio cae entre guardar en la base y publicar en Kafka. | Patrón outbox: cambio y evento en la misma transacción; prueba que mata el servicio |

### Disponibilidad y recuperación

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-13 | Tras un reinicio, el motor reconstruye el libro en ≤ 30 s desde la última instantánea y los comandos posteriores, sin perder ni duplicar órdenes. | Prueba que reinicia el motor con 5.000 órdenes abiertas y compara el libro |
| RNF-14 | Si un servicio cae, los demás siguen operando; sus eventos esperan en Kafka y se procesan al volver. | Prueba que detiene portfolio-service 2 minutos durante la carga |
| RNF-15 | Los tópicos de órdenes y operaciones guardan 7 días de mensajes; los compactados conservan el último valor de cada clave. `users.registered` no vence: cualquier servicio nuevo necesita leer todos los registros. | Configuración `retention.ms` y `cleanup.policy` revisada |
| RNF-16 | La base de datos tiene respaldo diario y la restauración está probada. | Restauración de prueba documentada una vez por sprint |
| RNF-17 | El sistema está disponible el 99 % del horario de mercado en el entorno de demostración. | Health checks registrados en Prometheus |

### Escalabilidad

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-18 | portfolio-service, notification-service y market-data-service escalan agregando instancias, hasta 6 (una por partición). | Prueba con 1 y con 3 instancias; el throughput debe subir |
| RNF-19 | El motor escala repartiendo instrumentos entre instancias; un instrumento nunca se divide entre dos. | Prueba con 2 instancias y 10 instrumentos; cada libro vive en una sola |
| RNF-20 | Agregar un instrumento no requiere cambiar código ni reiniciar servicios. | Crear un instrumento con el sistema en carga y operar en él |

### Seguridad

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-21 | Las contraseñas se guardan con BCrypt (factor 10 o más) y nunca aparecen en texto plano ni en logs. | Revisión de código y búsqueda en logs |
| RNF-22 | Todo endpoint exige un JWT válido, salvo registro, login y datos públicos de mercado; el gateway valida firma y vencimiento. | Pruebas sin token, con token vencido y con firma alterada |
| RNF-23 | Cada usuario solo puede ver y cancelar sus propias órdenes, movimientos y alertas. | Prueba que intenta cancelar la orden de otro usuario (debe dar 403) |
| RNF-24 | El gateway limita a 20 órdenes por segundo por usuario; el exceso recibe 429. | Prueba k6 que supera el límite |
| RNF-25 | Todas las entradas se validan y las consultas son parametrizadas, contra inyección SQL y XSS. | Bean Validation en los DTO; escaneo con OWASP ZAP |
| RNF-26 | Las claves JWT y contraseñas de base de datos van en variables de entorno, nunca en el repositorio. | Escaneo de secretos en la integración continua |
| RNF-27 | El despliegue usa HTTPS y el WebSocket usa WSS. | Revisión de la configuración del gateway |

### Cumplimiento legal y protección de datos

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-28 | El tratamiento de datos personales cumple la Ley 1581 de 2012: política de tratamiento visible, autorización expresa al registrarse y derecho a conocer, actualizar, rectificar y suprimir los datos. | Casilla de autorización obligatoria; opciones de editar y eliminar cuenta |
| RNF-29 | Toda pantalla muestra que es una simulación educativa con dinero virtual, que no ofrece asesoría ni valores reales. | Revisión visual de cada pantalla |
| RNF-30 | El sistema solo pide nombre, correo y contraseña (minimización de datos). | Revisión del formulario de registro |

El aviso de RNF-29 evita que la plataforma se confunda con una actividad de intermediación de valores, que en Colombia supervisa la Superintendencia Financiera.

### Observabilidad

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-31 | Cada servicio expone métricas con Actuator y Micrometer; Grafana muestra latencia del motor, órdenes por segundo y lag por consumidor. | Tablero de Grafana incluido en el repositorio |
| RNF-32 | Los logs salen en JSON con un id de correlación que viaja en los headers de Kafka, para seguir una orden por todos los servicios. | Buscar un id de orden y ver su recorrido completo |
| RNF-33 | Cada servicio tiene health checks de vida y disponibilidad. | Endpoints `/actuator/health/liveness` y `/readiness` |
| RNF-34 | Se genera una alerta si el lag supera 5.000 mensajes o la latencia p95 del motor pasa de 100 ms. | Regla de alerta en Prometheus disparada en prueba |

### Mantenibilidad y pruebas

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-35 | La cobertura de pruebas es ≥ 70 % en la lógica de negocio y ≥ 90 % en el motor de emparejamiento. | Reporte de JaCoCo en la integración continua |
| RNF-36 | Cada servicio tiene pruebas de integración con Kafka y PostgreSQL reales en contenedores. | Testcontainers en la suite de pruebas |
| RNF-37 | Los eventos tienen esquemas versionados en un módulo compartido; un cambio nunca rompe a los consumidores existentes. | Prueba de compatibilidad al cambiar un esquema |
| RNF-38 | La lógica de cruce del motor no depende de Kafka ni de Spring (arquitectura hexagonal), para probarla aislada. | Pruebas unitarias del libro sin levantar Spring |
| RNF-39 | Cada pull request se compila, se prueba y se analiza; no se fusiona si algo falla. | Pipeline de GitHub Actions obligatorio en la rama principal |
| RNF-40 | Toda la API REST está documentada con OpenAPI. | Swagger UI accesible en cada servicio |

### Usabilidad

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-41 | La interfaz está en español y funciona desde 360 px de ancho (celular). | Prueba en un celular y en el modo responsive del navegador |
| RNF-42 | Compra y venta se distinguen con texto o ícono, no solo con color; el contraste cumple WCAG 2.1 nivel AA. | Revisión con una herramienta de contraste |
| RNF-43 | Un usuario nuevo coloca su primera orden en menos de 2 minutos sin ayuda. | Prueba con 5 personas que no conocen el sistema |
| RNF-44 | Los errores dicen qué pasó y qué hacer, por ejemplo: "Saldo insuficiente: tienes 1.200.000 COP disponibles". | Revisión de todos los mensajes de error |

### Portabilidad y despliegue

| ID | Requisito | Cómo se comprueba |
| --- | --- | --- |
| RNF-45 | `docker compose up` levanta todo el sistema en ≤ 3 minutos en el equipo de referencia. | Medición en un equipo limpio |
| RNF-46 | La configuración se separa por perfiles (dev, test, prod) y variables de entorno. | Arrancar con cada perfil sin tocar el código |
| RNF-47 | Cada servicio tiene su imagen Docker, construida en la integración continua. | Imágenes publicadas por el pipeline |
| RNF-48 | El sistema corre igual en Windows, macOS y Linux con Docker instalado. | Prueba de arranque en al menos dos sistemas operativos |

## Parte 4 · Historias de usuario

### Formato y escala

BolsaSim tiene 27 historias de usuario en 6 épicas, con 97 puntos en total. Cada una sigue la forma "Como \[rol\], quiero \[acción\], para \[beneficio\]" y tiene criterios de aceptación en formato Dado / Cuando / Entonces.

- **Puntos de historia** (escala 1, 2, 3, 5, 8): miden tamaño relativo. 1 es un cambio pequeño en un servicio; 8 toca varios servicios y requiere pruebas de integración.
- **Prioridad:** la misma escala MoSCoW de los requisitos funcionales (Debe, Debería, Podría).
- **Trazabilidad:** cada historia indica los RF de la parte 2 que cubre.

**Definición de terminado.** Una historia está terminada cuando:

1. El código está revisado y fusionado mediante pull request.
2. Las pruebas unitarias y de integración pasan en la integración continua.
3. Cada criterio de aceptación se comprobó en el entorno de Docker Compose.
4. Los endpoints nuevos están documentados en OpenAPI.
5. Los eventos nuevos tienen su esquema en el módulo compartido.

### Épica 1: registro y acceso

**HU-01 · Registrarme**

Como estudiante, quiero crear una cuenta con mi correo, para empezar a operar con dinero virtual.

- Dado un correo no registrado, cuando envío nombre, alias, correo y contraseña y acepto la política de datos, entonces se crea mi cuenta con 10.000.000 COP virtuales.
- Dado un correo ya registrado, cuando intento registrarme, entonces veo "Ese correo ya tiene una cuenta".
- Dado que no acepto la política de datos, cuando intento registrarme, entonces el botón Registrarme queda desactivado.
- Dada una contraseña de menos de 8 caracteres, cuando la escribo, entonces veo el error antes de enviar.

*RF-01, RF-07 · 3 puntos · Debe*

**HU-02 · Iniciar y cerrar sesión**

Como inversionista, quiero iniciar y cerrar sesión, para que nadie más use mi cuenta.

- Dadas credenciales correctas, cuando inicio sesión, entonces entro a la pantalla de mercado.
- Dadas credenciales incorrectas, cuando inicio sesión, entonces veo "Correo o contraseña incorrectos", sin decir cuál falló.
- Dado que mi token de 15 minutos venció, cuando sigo usando la app, entonces se renueva solo con el token de refresco.
- Dado que cierro sesión, cuando alguien intenta usar mi token de refresco, entonces el sistema lo rechaza.

*RF-02, RF-03, RF-06 · 3 puntos · Debe*

**HU-03 · Cambiar mi alias**

Como inversionista, quiero cambiar mi alias, para elegir cómo aparezco en el ranking.

- Dado un alias libre de 3 a 30 caracteres, cuando lo guardo, entonces aparece así en el ranking.
- Dado un alias que ya usa otra persona, cuando lo guardo, entonces veo "Ese alias ya está en uso".

*RF-05 · 1 punto · Debería*

**HU-04 · Recuperar mi contraseña**

Como inversionista, quiero recuperar mi contraseña por correo, para no perder mi cuenta si la olvido.

- Dado mi correo registrado, cuando pido recuperar la contraseña, entonces recibo un enlace que vence en 30 minutos.
- Dado que ya usé el enlace, cuando lo abro otra vez, entonces el sistema lo rechaza.

*RF-04 · 3 puntos · Podría*

### Épica 2: operar en el mercado

**HU-05 · Comprar a precio límite**

Como inversionista, quiero comprar acciones fijando un precio máximo, para no pagar más de lo que estoy dispuesto.

- Dado que tengo 1.000.000 COP disponibles y el mercado está abierto, cuando envío una compra límite de 100 acciones a 5.000, entonces se reservan 500.000 COP y la orden queda Abierta.
- Dado que en el libro hay una venta de 100 a 4.900, cuando envío esa compra, entonces se ejecuta a 4.900 y se liberan los 10.000 COP sobrantes.
- Dado que no tengo saldo suficiente, cuando envío la orden, entonces queda Rechazada con "Saldo insuficiente: tienes X COP disponibles".
- Dado un precio que no es múltiplo del tick o está a más de 10 % del último precio, cuando intento enviar, entonces veo el error y no se envía nada.

*RF-15, RF-16, RF-17, RF-18, RF-23, RF-24, RF-25 · 8 puntos · Debe*

**HU-06 · Vender a precio límite**

Como inversionista, quiero vender mis acciones fijando un precio mínimo, para asegurar la ganancia que busco.

- Dado que tengo 100 acciones libres, cuando envío una venta límite de 60, entonces se reservan 60 y me quedan 40 libres.
- Dado que intento vender más acciones de las que tengo libres, cuando envío la orden, entonces queda Rechazada con el motivo.
- Dado que mi venta se ejecuta, cuando reviso mi saldo, entonces el dinero ya está disponible y las acciones salieron de mi posición.

*RF-15, RF-17, RF-18, RF-30 · 5 puntos · Debe*

**HU-07 · Operar a precio de mercado**

Como inversionista, quiero comprar o vender al mejor precio disponible, para operar rápido sin calcular un precio.

- Dada una compra a mercado de 10 acciones con último precio 5.000, cuando la envío, entonces se reservan 55.000 COP (10 % extra).
- Dado que hay ventas en el libro dentro de la banda de ±10 %, cuando llega mi orden, entonces se ejecuta contra los mejores precios disponibles.
- Dado que no hay suficiente contraparte en la banda, cuando termina el cruce, entonces lo no ejecutado se cancela y la reserva sobrante se libera.

*RF-17, RF-26, RF-31 · 5 puntos · Debe*

**HU-08 · Cancelar una orden**

Como inversionista, quiero cancelar una orden abierta, para cambiar de estrategia.

- Dada una orden Abierta, cuando la cancelo, entonces pasa a Cancelada y mi reserva se libera en menos de 1 segundo.
- Dada una orden Parcial, cuando la cancelo, entonces se cancela solo lo pendiente y lo ejecutado se mantiene.
- Dada una orden Ejecutada o Rechazada, cuando la veo, entonces no aparece el botón Cancelar.

*RF-19, RF-31 · 3 puntos · Debe*

**HU-09 · Ver mis órdenes**

Como inversionista, quiero ver mis órdenes abiertas y mi historial, para saber qué pasó con cada una.

- Dado que tengo órdenes, cuando abro Mis órdenes, entonces veo estado, cantidad ejecutada y precio promedio de cada una.
- Dado que filtro por estado, cuando elijo Abiertas, entonces solo veo las Abiertas y Parciales.
- Dado que una orden cambia de estado, cuando tengo la lista abierta, entonces se actualiza sin recargar la página.

*RF-20 · 3 puntos · Debe*

**HU-10 · Evitar órdenes duplicadas**

Como inversionista, quiero que un doble clic no cree dos órdenes, para no gastar de más por error.

- Dado que presiono Comprar dos veces seguidas, cuando el sistema recibe las dos solicitudes, entonces solo existe una orden.
- Dado que la red falla y la app reintenta, cuando llega el reintento, entonces el sistema devuelve la orden original.

*RF-22 · 2 puntos · Debe*

### Épica 3: portafolio y saldo

**HU-11 · Ver mi portafolio**

Como inversionista, quiero ver mis acciones y cuánto valen ahora, para saber si estoy ganando o perdiendo.

- Dado que tengo posiciones, cuando abro Portafolio, entonces veo por acción: cantidad, precio promedio, valor actual y ganancia o pérdida en COP y en %.
- Dado que cambia el precio de una acción que tengo, cuando miro el portafolio, entonces el valor total se actualiza sin recargar.
- Dado que compré 100 a 5.000 y luego 100 a 6.000, cuando veo la posición, entonces el precio promedio es 5.500.

*RF-32, RF-33 · 5 puntos · Debe*

**HU-12 · Ver mi saldo y mis movimientos**

Como inversionista, quiero ver mi saldo y cada movimiento de dinero, para entender de dónde sale cada peso.

- Dado que tengo órdenes abiertas, cuando veo mi saldo, entonces aparece separado en disponible, reservado y total.
- Dado que abro Movimientos, cuando filtro por tipo Compra, entonces solo veo compras, con fecha y orden de origen.

*RF-08, RF-09, RF-10 · 3 puntos · Debe*

**HU-13 · Ver la evolución de mi portafolio**

Como inversionista, quiero ver un gráfico del valor de mi portafolio día a día, para ver mi progreso.

- Dado que llevo varios días operando, cuando abro el gráfico, entonces veo un punto por cada cierre de mercado.

*RF-34 · 3 puntos · Podría*

### Épica 4: mercado en tiempo real

**HU-14 · Ver la lista de instrumentos**

Como inversionista, quiero ver todas las acciones con su precio, para decidir en cuál operar.

- Dado que el mercado tiene instrumentos, cuando abro Mercado, entonces veo símbolo, nombre, sector, último precio y variación del día.
- Dado que escribo "AND" en el buscador, cuando filtro, entonces solo veo instrumentos cuyo símbolo o nombre contiene ese texto.
- Dado que hay una operación nueva, cuando tengo la lista abierta, entonces el precio cambia en vivo.

*RF-11, RF-13, RF-40 · 3 puntos · Debe*

**HU-15 · Ver el gráfico de velas**

Como inversionista, quiero ver el gráfico de velas de una acción, para entender hacia dónde se mueve el precio.

- Dado que abro la ficha de un instrumento, cuando carga, entonces veo el historial de velas de 1 minuto.
- Dado que elijo 5 minutos, cuando cambio el intervalo, entonces el gráfico se redibuja con velas de 5 minutos.
- Dado que hay operaciones nuevas, cuando miro el gráfico, entonces la vela actual crece en vivo.

*RF-37, RF-38 · 5 puntos · Debe*

**HU-16 · Ver el libro y las últimas operaciones**

Como inversionista, quiero ver quién compra y quién vende y a qué precio, para elegir bien mi precio límite.

- Dado que abro la ficha, cuando carga, entonces veo los 5 mejores precios de compra y de venta con su cantidad.
- Dado que alguien envía o cancela una orden, cuando tengo la ficha abierta, entonces el libro se actualiza en menos de 1 segundo.
- Dado que hay operaciones, cuando miro la ficha, entonces veo las últimas 20 con hora, precio y cantidad.

*RF-12, RF-36, RF-39 · 5 puntos · Debe*

**HU-17 · Explorar sin cuenta**

Como visitante, quiero ver precios, gráficos y ranking sin registrarme, para conocer la plataforma antes de crear una cuenta.

- Dado que no he iniciado sesión, cuando abro la app, entonces veo el mercado y el ranking en modo solo lectura.
- Dado que intento comprar sin sesión, cuando presiono Comprar, entonces se me pide iniciar sesión o registrarme.

*RF-06, RF-11 · 2 puntos · Debería*

**HU-18 · Seguir instrumentos**

Como inversionista, quiero marcar acciones como favoritas, para encontrarlas rápido.

- Dado un instrumento, cuando lo marco con la estrella, entonces aparece en Mi lista.
- Dado un instrumento de Mi lista, cuando quito la estrella, entonces desaparece de ella.

*RF-14 · 2 puntos · Podría*

### Épica 5: ranking, alertas y notificaciones

**HU-19 · Ver el ranking**

Como inversionista, quiero ver mi lugar entre todos los participantes, para medir mi desempeño.

- Dado que hay inversionistas operando, cuando abro Ranking, entonces veo el top 50 por rentabilidad, sin bots ni administradores.
- Dado que no estoy en el top 50, cuando abro Ranking, entonces veo mi posición y mi rentabilidad al final de la lista.
- Dado que mi portafolio sube de valor, cuando pasa como máximo un minuto, entonces mi posición en el ranking se actualiza.

*RF-41, RF-42, RF-43, RF-44 · 3 puntos · Debe*

**HU-20 · Recibir avisos de mis órdenes**

Como inversionista, quiero enterarme al instante de lo que pasa con mis órdenes, para no tener que revisarlas a cada rato.

- Dado que mi orden se ejecuta, se ejecuta en parte, se cancela o se rechaza, cuando ocurre, entonces veo un aviso en pantalla en menos de 1 segundo.
- Dado que tengo avisos sin leer, cuando abro el centro de notificaciones, entonces puedo marcarlos como leídos.

*RF-47, RF-48 · 3 puntos · Debe*

**HU-21 · Crear una alerta de precio**

Como inversionista, quiero que me avisen cuando una acción llegue a cierto precio, para no estar mirando el gráfico todo el día.

- Dada una alerta "ANDX sube a 6.000", cuando una operación se hace a 6.000 o más, entonces recibo una notificación.
- Dado que la alerta ya se disparó, cuando el precio vuelve a cumplir la condición, entonces no recibo otro aviso.

*RF-45, RF-46 · 3 puntos · Debería*

### Épica 6: administración y bots

**HU-22 · Gestionar instrumentos**

Como administrador, quiero crear, editar y suspender instrumentos, para controlar qué se negocia.

- Dado un símbolo nuevo, cuando creo el instrumento con nombre, sector, precio inicial, tick y acciones emitidas, entonces aparece en el mercado sin reiniciar nada.
- Dado que creo un instrumento, cuando se registra, entonces todas sus acciones quedan en la cuenta del bot creador de mercado.
- Dado un símbolo que ya existe, cuando intento crearlo, entonces veo "Ese símbolo ya existe".
- Dado un instrumento suspendido, cuando alguien envía una orden nueva, entonces se rechaza, pero las cancelaciones sí funcionan.

*RF-29, RF-54, RF-59 · 5 puntos · Debe*

**HU-23 · Abrir y cerrar el mercado**

Como administrador, quiero abrir y cerrar el mercado a mano o por horario, para organizar las sesiones de práctica.

- Dado el modo horario de 8:00 a 16:00, cuando llega cada hora, entonces el mercado abre o cierra solo.
- Dado que el mercado cierra, cuando termina el cierre, entonces las órdenes límite abiertas se cancelan y sus reservas se liberan.
- Dado el mercado cerrado, cuando alguien envía una orden, entonces se rechaza con "El mercado está cerrado".

*RF-16, RF-21, RF-55 · 3 puntos · Debe*

**HU-24 · Configurar bots de mercado**

Como administrador, quiero crear y controlar bots, para que el mercado tenga movimiento aunque haya pocos usuarios.

- Dado un bot creador de mercado para ANDX con margen de 2 ticks, cuando lo enciendo, entonces mantiene una compra y una venta a 2 ticks del último precio.
- Dado un bot encendido, cuando lo apago, entonces deja de enviar órdenes y sus órdenes abiertas se cancelan.
- Dado un bot sin saldo suficiente, cuando envía una orden, entonces se rechaza igual que la de cualquier usuario.

*RF-49, RF-50, RF-51, RF-52, RF-53 · 8 puntos · Debe*

**HU-25 · Reiniciar la temporada**

Como administrador, quiero reiniciar todos los portafolios, para empezar una competencia nueva.

- Dado que confirmo escribiendo REINICIAR, cuando se ejecuta, entonces cada inversionista vuelve al saldo inicial, sin acciones ni órdenes abiertas.
- Dado el reinicio, cuando reviso los movimientos de una cuenta, entonces aparece uno de tipo REINICIO.

*RF-56 · 3 puntos · Debería*

**HU-26 · Bloquear usuarios**

Como administrador, quiero bloquear a un usuario, para detener un uso indebido de la plataforma.

- Dado un usuario bloqueado, cuando intenta iniciar sesión, entonces el sistema lo rechaza.
- Dado que bloqueo a un usuario, cuando se aplica, entonces sus órdenes abiertas se cancelan.

*RF-57 · 2 puntos · Debería*

**HU-27 · Ver métricas del mercado**

Como administrador, quiero ver la actividad del mercado en un panel, para saber si el sistema y los bots funcionan bien.

- Dado que el mercado está abierto, cuando abro el panel, entonces veo órdenes por minuto, operaciones del día y usuarios activos, actualizados cada 10 segundos.

*RF-58 · 3 puntos · Podría*

### Backlog priorizado por sprint

Las 23 historias de la versión 1 suman 86 puntos en 6 sprints; las 4 de la fase 2 (11 puntos) entran solo si sobra tiempo. El sprint 3 es el más cargado (21 puntos) porque concentra el motor; si se atrasa, HU-07 pasa al sprint 4.

| Sprint | HU | Historia | Puntos | Estado |
| --- | --- | --- | --- | --- |
| 1 | HU-01 | Registrarme | 3 | Hecho |
| 1 | HU-02 | Iniciar y cerrar sesión | 3 | Hecho |
| 2 | HU-22 | Gestionar instrumentos | 5 | Hecho, con pendientes (1) |
| 2 | HU-12 | Ver mi saldo y mis movimientos | 3 | Hecho, con pendientes (2) |
| 2 | HU-09 | Ver mis órdenes | 3 | Hecho, con pendientes (3) |
| 2 | HU-10 | Evitar órdenes duplicadas | 2 | Hecho |
| 3 | HU-05 | Comprar a precio límite | 8 | Hecho, con pendientes (4) |
| 3 | HU-06 | Vender a precio límite | 5 | Hecho |
| 3 | HU-07 | Operar a precio de mercado | 5 | Hecho |
| 3 | HU-08 | Cancelar una orden | 3 | Hecho |
| 4 | HU-11 | Ver mi portafolio | 5 | Hecho, con pendientes (6) |
| 4 | HU-14 | Ver la lista de instrumentos | 3 | En curso (5) |
| 4 | HU-15 | Ver el gráfico de velas | 5 | Por hacer |
| 4 | HU-16 | Ver el libro y las últimas operaciones | 5 | Por hacer |
| 5 | HU-24 | Configurar bots de mercado | 8 | Por hacer |
| 5 | HU-23 | Abrir y cerrar el mercado | 3 | Por hacer |
| 5 | HU-20 | Recibir avisos de mis órdenes | 3 | Por hacer |
| 5 | HU-17 | Explorar sin cuenta | 2 | Por hacer |
| 6 | HU-19 | Ver el ranking | 3 | Por hacer |
| 6 | HU-21 | Crear una alerta de precio | 3 | Por hacer |
| 6 | HU-25 | Reiniciar la temporada | 3 | Por hacer |
| 6 | HU-26 | Bloquear usuarios | 2 | Por hacer |
| 6 | HU-03 | Cambiar mi alias | 1 | Por hacer |
| Fase 2 | HU-04 | Recuperar mi contraseña | 3 | Por hacer |
| Fase 2 | HU-13 | Ver la evolución de mi portafolio | 3 | Por hacer |
| Fase 2 | HU-18 | Seguir instrumentos | 2 | Por hacer |
| Fase 2 | HU-27 | Ver métricas del mercado | 3 | Por hacer |

**Pendientes de las historias hechas** (estado al 5 de noviembre de 2026):

1. **HU-22:** las órdenes de un instrumento suspendido ya se pueden cancelar (HU-08). Las acciones emitidas de cada instrumento ya quedan en la cuenta del bot creador de mercado (RF-59), que auth-service crea al arrancar; los bots que operan solos llegan con HU-24.
2. **HU-12:** el movimiento de reinicio llega con HU-25. Los de reserva, liberación, compra, venta y emisión ya llegan.
3. **HU-09:** la actualización de la lista sin recargar la página llega con el WebSocket (sprint 4). Los estados, la cantidad ejecutada y el precio promedio ya se actualizan con `orders.events`, y el rechazo por saldo con `reservations.events`.
4. **HU-05:** validar que el mercado esté abierto (RF-16) llega con HU-23; hasta entonces el mercado está siempre abierto. Una compra a mercado reserva cantidad × último precio × 1,10, y el motor la ejecuta solo dentro de ±10 % de ese mismo precio (RF-26, HU-07), así la reserva siempre alcanza. La liquidación usa `orders.events` y no `trades.executed`: ahí llegan, en orden y por la misma partición, las ejecuciones de cada orden y su cancelación.
5. **HU-14:** la pantalla Mercado ya muestra los instrumentos con su precio inicial, su estado y un buscador por símbolo o nombre; HU-01 y HU-02 ya tienen sus pantallas. Faltan el último precio, la variación del día y la actualización en vivo por WebSocket.
6. **HU-11:** `GET /api/portafolio` y la pantalla Portafolio ya muestran cada posición con cantidad, precio promedio, valor actual y ganancia o pérdida en COP y en % (RF-32), y el valor total (RF-33). El precio actual es el de la última operación, que portfolio-service copia de `trades.executed` en ULTIMO_PRECIO; sin operaciones, el precio inicial. Falta el criterio "el valor total se actualiza sin recargar": llega con el WebSocket (sprint 4); mientras tanto la pantalla tiene un botón Actualizar.
