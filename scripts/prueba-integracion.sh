#!/usr/bin/env bash
# Prueba de punta a punta, todo a través del gateway (puerto 8080): levanta Kafka y PostgreSQL, arranca
# auth-service (con un ADMIN nuevo), order-service, market-data-service, portfolio-service, matching-engine y el gateway,
# registra un usuario (y comprueba que llega a users.registered y a order-service), inicia sesión,
# comprueba que el perfil de otro usuario responde 404, que sin token se recibe 401 (también llamando
# directo a auth-service, order-service y market-data-service, sin el gateway) y que una orden con
# usuarioId en el cuerpo recibe 400. Después el ADMIN crea un instrumento nuevo con todo corriendo
# (un inversionista recibe 403), lo suspende (las órdenes reciben 422) y lo reactiva; un precio fuera del tick
# o de la banda de ±10 % recibe 422. Con el token se envían una venta y una compra de ese instrumento que se
# cruzan: cada una pide su reserva a portfolio-service, y se comprueba que sus comandos salen del outbox y que el
# motor registra la ejecución a nombre del usuario del token; la compra se reenvía con la misma Idempotency-Key y
# a orders.commands llega un solo comando. Las órdenes se consultan por id y en la lista paginada, y otro usuario
# recibe 404. Luego, los criterios de HU-05: con una venta de 100 a 4900 en el libro, una compra de 100 a 5000 se
# ejecuta a 4900 y se liberan los 10.000 sobrantes (saldo, reservado y movimientos cuadran); una compra sin
# contraparte queda ABIERTA con su dinero reservado, y una sin saldo suficiente queda RECHAZADA con el motivo.
# Las ventas son del creador de mercado, que auth-service crea al arrancar y que recibe en portfolio-service las
# acciones emitidas del instrumento nuevo (RF-59); una venta de quien no tiene acciones queda RECHAZADA. Al final
# se comprueba por la API que el creador cobró lo que pagó el comprador (HU-06). Después se cancelan dos órdenes
# (HU-08): una compra que no cruzó devuelve todo su dinero, y una ejecutada en parte queda CANCELADA con lo
# ejecutado y devuelve solo lo pendiente. Por último renueva el token y comprueba que, después de cerrar sesión, el
# token de refresco ya no sirve.
#
# Requisitos: Docker abierto, los jars compilados (./mvnw -B verify) y los servicios detenidos
# (la prueba arranca los suyos en los puertos 8080 a 8084).
#
#   bash scripts/prueba-integracion.sh
set -euo pipefail
cd "$(dirname "$0")/.."

LOGS=target/integracion
mkdir -p "$LOGS"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
GATEWAY=http://localhost:8080
ORDER_SERVICE=http://localhost:8081
AUTH_SERVICE=http://localhost:8082
MARKET_DATA_SERVICE=http://localhost:8083
PORTFOLIO_SERVICE=http://localhost:8084

for puerto in 8080 8081 8082 8083 8084; do
    if curl -s -o /dev/null "http://localhost:$puerto"; then
        echo "El puerto $puerto está ocupado: detén el gateway y los servicios antes de correr la prueba."
        exit 1
    fi
done

# Espera hasta que un texto aparezca en un log; si no aparece, muestra el log y falla.
esperar() {
    local texto=$1 archivo=$2 segundos=$3
    for _ in $(seq 1 "$segundos"); do
        grep -q "$texto" "$archivo" && return 0
        sleep 1
    done
    echo "No apareció \"$texto\" en $archivo después de ${segundos}s:"
    cat "$archivo"
    return 1
}

# Una Idempotency-Key nueva con forma de UUID (Git Bash no trae uuidgen).
nueva_clave() {
    printf '%04x%04x-%04x-%04x-%04x-%04x%04x%04x' $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM
}

# Hace una petición al gateway y devuelve el código HTTP; el cuerpo queda en $LOGS/respuesta.json y los
# headers en $LOGS/cabeceras.txt. Cada orden nueva (POST /api/ordenes) lleva una Idempotency-Key nueva;
# CLAVE=... reenvía una clave en particular y CLAVE=ninguna la omite.
# Uso: pedir MÉTODO RUTA [CUERPO] [TOKEN]
pedir() {
    local metodo=$1 ruta=$2 cuerpo=${3:-} token=${4:-}
    local argumentos=(-s -o "$LOGS/respuesta.json" -D "$LOGS/cabeceras.txt" -w "%{http_code}" -X "$metodo" "$GATEWAY$ruta")
    if [ -n "$cuerpo" ]; then
        argumentos+=(-H "Content-Type: application/json" -d "$cuerpo")
    fi
    if [ -n "$token" ]; then
        argumentos+=(-H "Authorization: Bearer $token")
    fi
    if [ "$metodo" = POST ] && [ "$ruta" = /api/ordenes ]; then
        local clave=${CLAVE:-$(nueva_clave)}
        if [ "$clave" != ninguna ]; then
            argumentos+=(-H "Idempotency-Key: $clave")
        fi
    fi
    curl "${argumentos[@]}"
}

# Falla con la respuesta a la vista si el código no es el esperado. Uso: comprobar DESCRIPCIÓN CÓDIGO ESPERADO
comprobar() {
    if [ "$2" != "$3" ]; then
        echo "$1: se esperaba $3 y llegó $2"
        cat "$LOGS/respuesta.json"
        exit 1
    fi
}

# Saca un campo de texto de la última respuesta JSON. Uso: campo NOMBRE
campo() {
    sed -E "s/.*\"$1\":\"([^\"]+)\".*/\1/" "$LOGS/respuesta.json"
}

# Saca un campo numérico de la última respuesta JSON. Uso: numero NOMBRE
numero() {
    sed -E "s/.*\"$1\":(-?[0-9]+).*/\1/" "$LOGS/respuesta.json"
}

# Consulta una ruta cada segundo hasta que la respuesta tenga el texto (por ejemplo, el estado que le pone el motor
# o la reserva, que llegan de forma asíncrona); si no aparece en 30 s, la muestra y falla.
# Uso: esperar_respuesta DESCRIPCIÓN RUTA TOKEN TEXTO
esperar_respuesta() {
    for _ in $(seq 1 30); do
        comprobar "$1" "$(pedir GET "$2" "" "$3")" 200
        grep -q "$4" "$LOGS/respuesta.json" && return 0
        sleep 1
    done
    echo "$1: la respuesta no llegó a tener $4:"
    cat "$LOGS/respuesta.json"
    exit 1
}

echo "1. Levantando Kafka y PostgreSQL..."
docker compose up -d --wait kafka postgres
docker compose up --exit-code-from kafka-init kafka-init > /dev/null
# init.sql solo corre la primera vez que arranca PostgreSQL: un contenedor de antes no tiene las bases nuevas
for base in mercado portafolio; do
    if ! docker exec bolsasim-postgres psql -U bolsasim -d postgres -tAc \
            "SELECT 1 FROM pg_database WHERE datname = '$base'" | grep -q 1; then
        docker exec bolsasim-postgres createdb -U bolsasim "$base"
        echo "   Base \"$base\" creada"
    fi
done

echo "2. Arrancando auth-service, market-data-service, portfolio-service, matching-engine, order-service y el gateway..."
# Alias y correos distintos en cada corrida: PostgreSQL conserva los usuarios mientras el contenedor exista.
# El ADMIN se crea al arrancar auth-service, con una contraseña aleatoria que no queda en ninguna parte.
SUFIJO=$(date +%s)
CORREO_ADMIN="admin_$SUFIJO@bolsasim.co"
CLAVE_ADMIN="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
# El creador de mercado es uno solo y la base lo conserva entre corridas: su correo y su contraseña son fijos, y
# sirven solo para esta prueba. En la primera corrida se crea; en las siguientes, auth-service vuelve a publicarlo
CORREO_CREADOR="creador_mercado@bolsasim.co"
CLAVE_CREADOR="creador-de-prueba-2026"
BOLSASIM_ADMIN_CORREO="$CORREO_ADMIN" BOLSASIM_ADMIN_CONTRASENA="$CLAVE_ADMIN" BOLSASIM_ADMIN_ALIAS="admin_$SUFIJO" \
BOLSASIM_CREADOR_MERCADO_CORREO="$CORREO_CREADOR" BOLSASIM_CREADOR_MERCADO_CONTRASENA="$CLAVE_CREADOR" \
    "$JAVA" -jar auth-service/target/auth-service-*[0-9T].jar > "$LOGS/auth-service.log" 2>&1 &
USUARIOS=$!
"$JAVA" -jar market-data-service/target/market-data-service-*[0-9T].jar > "$LOGS/market-data-service.log" 2>&1 &
MERCADO=$!
"$JAVA" -jar portfolio-service/target/portfolio-service-*[0-9T].jar > "$LOGS/portfolio-service.log" 2>&1 &
CUENTAS=$!
"$JAVA" -jar matching-engine/target/matching-engine-*[0-9T].jar > "$LOGS/matching-engine.log" 2>&1 &
MOTOR=$!
"$JAVA" -jar order-service/target/order-service-*[0-9T].jar > "$LOGS/order-service.log" 2>&1 &
ORDENES=$!
"$JAVA" -jar gateway/target/gateway-*[0-9T].jar > "$LOGS/gateway.log" 2>&1 &
ENTRADA=$!
trap 'kill $USUARIOS $MERCADO $CUENTAS $MOTOR $ORDENES $ENTRADA 2>/dev/null || true' EXIT

esperar "Started AuthServiceApplication" "$LOGS/auth-service.log" 60
esperar "Administrador admin_$SUFIJO creado" "$LOGS/auth-service.log" 30
# Recién creado o, en las corridas siguientes, publicado otra vez
esperar "Usuario .* publicado en users.registered" "$LOGS/auth-service.log" 30
esperar "Started MarketDataServiceApplication" "$LOGS/market-data-service.log" 60
esperar "Started PortfolioServiceApplication" "$LOGS/portfolio-service.log" 60
esperar "Started OrderServiceApplication" "$LOGS/order-service.log" 60
esperar "Started GatewayApplication" "$LOGS/gateway.log" 60
esperar "partitions assigned" "$LOGS/matching-engine.log" 60
# market-data-service publica los 10 instrumentos iniciales al arrancar (si order-service ya los tenía de
# una corrida anterior, el log dice "actualizado" en vez de "guardado")
esperar "Instrumento ANDX .*copia local" "$LOGS/order-service.log" 60

echo "3. Registrando un usuario a través del gateway..."
CORREO="prueba_$SUFIJO@bolsasim.co"
comprobar "Registro" "$(pedir POST /api/usuarios "{\"nombre\":\"Usuario de prueba\",\"alias\":\"prueba_$SUFIJO\",
    \"correo\":\"$CORREO\",\"contrasena\":\"secreta123\",\"aceptaPoliticaDatos\":true}")" 201
ID=$(campo id)
echo "   Usuario $ID"

# MSYS_NO_PATHCONV: en Git Bash de Windows evita que /opt/... se convierta en una ruta de Windows
if ! MSYS_NO_PATHCONV=1 docker exec bolsasim-kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic users.registered --from-beginning --timeout-ms 10000 \
        2> /dev/null | grep -q "$ID"; then
    echo "El evento del usuario $ID no llegó a users.registered:"
    cat "$LOGS/auth-service.log"
    exit 1
fi
echo "   Evento UsuarioRegistrado publicado en users.registered"

# order-service guarda el usuario en su copia local de forma asíncrona: hay que esperarlo
esperar "Usuario $ID guardado en la copia local" "$LOGS/order-service.log" 30
echo "   order-service ya conoce al usuario"
esperar "Cuenta del usuario $ID abierta con 10000000 COP" "$LOGS/portfolio-service.log" 30
echo "   portfolio-service le abrió la cuenta con 10.000.000 COP"

echo "4. Iniciando sesión..."
comprobar "Login" "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO\",\"contrasena\":\"secreta123\"}")" 200
TOKEN=$(campo tokenAcceso)
REFRESCO=$(campo tokenRefresco)
comprobar "Consultar el usuario con el token" "$(pedir GET "/api/usuarios/$ID" "" "$TOKEN")" 200
comprobar "Login con contraseña equivocada" \
    "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO\",\"contrasena\":\"equivocada\"}")" 401
echo "   Login 200, consulta con token 200 y contraseña equivocada 401"

echo "   Consultando la cuenta..."
comprobar "Saldo de la cuenta" "$(pedir GET /api/cuenta "" "$TOKEN")" 200
grep -q '"saldoDisponible":10000000,"saldoReservado":0,"saldoTotal":10000000' "$LOGS/respuesta.json"
comprobar "Movimientos de la cuenta" "$(pedir GET "/api/cuenta/movimientos?tipo=SALDO_INICIAL" "" "$TOKEN")" 200
grep -q '"tipo":"SALDO_INICIAL","monto":10000000' "$LOGS/respuesta.json"
comprobar "Movimientos con un tipo que no existe" "$(pedir GET "/api/cuenta/movimientos?tipo=REGALO" "" "$TOKEN")" 400
comprobar "Cuenta directo a portfolio-service sin token" "$(GATEWAY=$PORTFOLIO_SERVICE pedir GET /api/cuenta)" 401
echo "   Saldo 10.000.000 (disponible) y 0 reservado; movimiento SALDO_INICIAL; tipo inválido 400; sin token 401"

echo "   Comprobando que nadie ve el perfil de otro..."
comprobar "Registro de un segundo usuario" "$(pedir POST /api/usuarios "{\"nombre\":\"Otro usuario\",\"alias\":\"otro_$SUFIJO\",
    \"correo\":\"otro_$SUFIJO@bolsasim.co\",\"contrasena\":\"secreta123\",\"aceptaPoliticaDatos\":true}")" 201
OTRO=$(campo id)
comprobar "Perfil de otro usuario" "$(pedir GET "/api/usuarios/$OTRO" "" "$TOKEN")" 404
# Saltándose el gateway: auth-service valida el token por su cuenta
comprobar "Perfil directo a auth-service sin token" "$(GATEWAY=$AUTH_SERVICE pedir GET "/api/usuarios/$ID")" 401
comprobar "Perfil directo a auth-service con un token alterado" \
    "$(GATEWAY=$AUTH_SERVICE pedir GET "/api/usuarios/$ID" "" "${TOKEN}x")" 401
# Un error de Spring en una ruta pública debe seguir siendo 400, no 401
comprobar "Registro que no es JSON, directo a auth-service" "$(GATEWAY=$AUTH_SERVICE pedir POST /api/usuarios "{no es json")" 400
echo "   Perfil de otro 404; directo a auth-service sin token o alterado 401; registro mal formado 400"

echo "5. El ADMIN crea un instrumento con todo corriendo..."
comprobar "Login del ADMIN" "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO_ADMIN\",\"contrasena\":\"$CLAVE_ADMIN\"}")" 200
TOKEN_ADMIN=$(campo tokenAcceso)
comprobar "Cuenta del ADMIN" "$(pedir GET /api/cuenta "" "$TOKEN_ADMIN")" 404
comprobar "Login del creador de mercado" \
    "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO_CREADOR\",\"contrasena\":\"$CLAVE_CREADOR\"}")" 200
TOKEN_CREADOR=$(campo tokenAcceso)
# Un símbolo de 5 letras al azar en cada corrida: la base conserva los instrumentos de corridas anteriores
LETRAS=ABCDEFGHIJKLMNOPQRSTUVWXYZ
SIMBOLO=""
for _ in 1 2 3 4 5; do SIMBOLO+=${LETRAS:RANDOM%26:1}; done
INSTRUMENTO="{\"simbolo\":\"$SIMBOLO\",\"nombre\":\"Empresa de prueba\",\"sector\":\"Pruebas\",
    \"precioInicial\":5000,\"tick\":10,\"accionesEmitidas\":100000}"
comprobar "Crear un instrumento sin token" "$(pedir POST /api/instrumentos "$INSTRUMENTO")" 401
comprobar "Crear un instrumento como inversionista" "$(pedir POST /api/instrumentos "$INSTRUMENTO" "$TOKEN")" 403
comprobar "Crear un instrumento directo a market-data-service sin token" \
    "$(GATEWAY=$MARKET_DATA_SERVICE pedir POST /api/instrumentos "$INSTRUMENTO")" 401
comprobar "Crear un instrumento directo a market-data-service con un token alterado" \
    "$(GATEWAY=$MARKET_DATA_SERVICE pedir POST /api/instrumentos "$INSTRUMENTO" "${TOKEN_ADMIN}x")" 401
comprobar "Crear el instrumento $SIMBOLO como ADMIN" "$(pedir POST /api/instrumentos "$INSTRUMENTO" "$TOKEN_ADMIN")" 201
comprobar "Crear $SIMBOLO otra vez" "$(pedir POST /api/instrumentos "$INSTRUMENTO" "$TOKEN_ADMIN")" 409
comprobar "Editar el nombre de $SIMBOLO" \
    "$(pedir PUT "/api/instrumentos/$SIMBOLO" '{"nombre":"Empresa de prueba S.A.","sector":"Pruebas"}' "$TOKEN_ADMIN")" 200
comprobar "Cambiar el tick de $SIMBOLO" \
    "$(pedir PUT "/api/instrumentos/$SIMBOLO" '{"nombre":"Empresa de prueba","sector":"Pruebas","tick":5}' "$TOKEN_ADMIN")" 400
comprobar "Consultar el catálogo como inversionista" "$(pedir GET /api/instrumentos "" "$TOKEN")" 200
grep -q "\"simbolo\":\"$SIMBOLO\"" "$LOGS/respuesta.json"
grep -q '"simbolo":"ANDX"' "$LOGS/respuesta.json"
esperar "Instrumento $SIMBOLO guardado en la copia local" "$LOGS/order-service.log" 30
# La oferta inicial: las 100.000 acciones emitidas van al creador de mercado
esperar "Emisión de $SIMBOLO: 100000 acciones a 5000 COP para el creador de mercado" "$LOGS/portfolio-service.log" 30
comprobar "Cuenta del creador de mercado" "$(pedir GET /api/cuenta "" "$TOKEN_CREADOR")" 200
CREADOR_ANTES=$(numero saldoDisponible)
comprobar "Emisión en los movimientos del creador" \
    "$(pedir GET "/api/cuenta/movimientos?tipo=EMISION&tamano=100" "" "$TOKEN_CREADOR")" 200
grep -q "\"tipo\":\"EMISION\",\"monto\":0,\"simbolo\":\"$SIMBOLO\",\"cantidad\":100000" "$LOGS/respuesta.json"
echo "   $SIMBOLO creado (201) y ya está en order-service; sin token 401, inversionista 403, repetido 409, tick 400"

echo "6. Comprobando las órdenes rechazadas..."
ORDEN="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":5000,\"cantidad\":10}"
comprobar "Orden sin token" "$(pedir POST /api/ordenes "$ORDEN")" 401
comprobar "Orden con un token alterado" "$(pedir POST /api/ordenes "$ORDEN" "${TOKEN}x")" 401
comprobar "Orden a nombre de otro usuario" "$(pedir POST /api/ordenes \
    "{\"usuarioId\":\"00000000-0000-0000-0000-000000000000\",${ORDEN#\{}" "$TOKEN")" 400
# Saltándose el gateway: order-service valida el token por su cuenta
comprobar "Orden directa a order-service sin token" "$(GATEWAY=$ORDER_SERVICE pedir POST /api/ordenes "$ORDEN")" 401
comprobar "Orden directa a order-service con un token alterado" \
    "$(GATEWAY=$ORDER_SERVICE pedir POST /api/ordenes "$ORDEN" "${TOKEN}x")" 401
comprobar "Orden de un instrumento que no existe" "$(pedir POST /api/ordenes "${ORDEN/$SIMBOLO/NADA}" "$TOKEN")" 422
comprobar "Suspender $SIMBOLO como inversionista" "$(pedir POST "/api/instrumentos/$SIMBOLO/suspender" "" "$TOKEN")" 403
comprobar "Suspender $SIMBOLO" "$(pedir POST "/api/instrumentos/$SIMBOLO/suspender" "" "$TOKEN_ADMIN")" 200
esperar "Instrumento $SIMBOLO ahora está SUSPENDIDO" "$LOGS/order-service.log" 30
comprobar "Orden de un instrumento suspendido" "$(pedir POST /api/ordenes "$ORDEN" "$TOKEN")" 422
comprobar "Reactivar $SIMBOLO" "$(pedir POST "/api/instrumentos/$SIMBOLO/reactivar" "" "$TOKEN_ADMIN")" 200
esperar "Instrumento $SIMBOLO ahora está ACTIVO" "$LOGS/order-service.log" 30
# RF-16: el tick de $SIMBOLO es 10 y su precio inicial 5000, así que la banda va de 4500 a 5500
comprobar "Orden con un precio que no es múltiplo del tick" "$(pedir POST /api/ordenes "${ORDEN/5000/5005}" "$TOKEN")" 422
grep -q "no es múltiplo del tick de $SIMBOLO" "$LOGS/respuesta.json"
comprobar "Orden a más de 10 % del último precio" "$(pedir POST /api/ordenes "${ORDEN/5000/5510}" "$TOKEN")" 422
grep -q "debe estar entre 4.500 y 5.500" "$LOGS/respuesta.json"
echo "   Sin token 401, token alterado 401, usuarioId en el cuerpo 400, instrumento inexistente 422,"
echo "   suspendido 422 hasta que se reactiva; directo a order-service también 401; fuera del tick o de la banda 422"

echo "7. Enviando con el token una venta y una compra de $SIMBOLO a 5000..."
# El usuario no tiene acciones: su venta se rechaza al reservarlas (HU-06, RF-18)
comprobar "Venta sin acciones" "$(pedir POST /api/ordenes "${ORDEN/COMPRA/VENTA}" "$TOKEN")" 202
VENTA=$(campo ordenId)
esperar_respuesta "Venta sin acciones" "/api/ordenes/$VENTA" "$TOKEN" '"estado":"RECHAZADA"'
grep -q "\"motivo\":\"Acciones insuficientes: tienes 0 de $SIMBOLO disponibles y la orden vende 10.\"" \
    "$LOGS/respuesta.json"
# El creador de mercado sí las tiene: deja su venta en el libro
comprobar "Venta del creador de mercado" "$(pedir POST /api/ordenes "${ORDEN/COMPRA/VENTA}" "$TOKEN_CREADOR")" 202
VENTA_CREADOR=$(campo ordenId)
esperar_respuesta "Venta del creador en el libro" "/api/ordenes/$VENTA_CREADOR" "$TOKEN_CREADOR" '"estado":"ABIERTA"'
CLAVE_COMPRA=$(nueva_clave)
comprobar "Compra" "$(CLAVE=$CLAVE_COMPRA pedir POST /api/ordenes "$ORDEN" "$TOKEN")" 202
COMPRA=$(campo ordenId)
# El doble clic: la misma compra con la misma clave recibe la misma orden y no crea otra
comprobar "Compra repetida con la misma clave" "$(CLAVE=$CLAVE_COMPRA pedir POST /api/ordenes "$ORDEN" "$TOKEN")" 202
grep -q "\"ordenId\":\"$COMPRA\"" "$LOGS/respuesta.json"
grep -qi "^Idempotent-Replayed: true" "$LOGS/cabeceras.txt"
OTRA_CANTIDAD=$(echo "$ORDEN" | sed 's/"cantidad":10/"cantidad":11/')
comprobar "La misma clave con otra orden" "$(CLAVE=$CLAVE_COMPRA pedir POST /api/ordenes "$OTRA_CANTIDAD" "$TOKEN")" 422
comprobar "Orden sin Idempotency-Key" "$(CLAVE=ninguna pedir POST /api/ordenes "$ORDEN" "$TOKEN")" 400

# Cada orden pide primero su reserva (clave = usuario) y portfolio-service responde; con la reserva aceptada, el
# comando sale del outbox. El motor anota el usuario de cada orden: solo puede venir del "sub" del token
esperar "Ejecución: 10 acciones de $SIMBOLO a 5000 COP .*($ID)" "$LOGS/matching-engine.log" 30
esperar "Evento .* del outbox publicado en orders.requested (clave $ID" "$LOGS/order-service.log" 10
esperar "Evento .* del outbox publicado en reservations.events (clave $ID" "$LOGS/portfolio-service.log" 10
esperar "Evento .* del outbox publicado en orders.commands (clave $SIMBOLO" "$LOGS/order-service.log" 10
echo "   La venta sin acciones quedó RECHAZADA; las reservas se aceptaron, los comandos salieron del outbox y el motor"
echo "   ejecutó la compra del usuario del token contra la venta del creador de mercado"

# La compra se envió dos veces con la misma clave: a orders.commands debe haber llegado un solo comando
COMANDOS=$(MSYS_NO_PATHCONV=1 docker exec bolsasim-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic orders.commands --from-beginning --timeout-ms 10000 \
    2> /dev/null | grep -c "$COMPRA" || true)
if [ "$COMANDOS" != 1 ]; then
    echo "La compra repetida con la misma Idempotency-Key llegó $COMANDOS veces a orders.commands, no 1"
    exit 1
fi
echo "   Compra repetida con la misma clave: 202 con la misma orden y un solo comando; otra orden con esa clave 422; sin clave 400"

echo "   Consultando las órdenes guardadas..."
# El motor publica en orders.events y order-service actualiza cada orden de forma asíncrona: hay que esperarlo
for orden in "$COMPRA:$TOKEN" "$VENTA_CREADOR:$TOKEN_CREADOR"; do
    token=${orden#*:}
    orden=${orden%%:*}
    for _ in $(seq 1 30); do
        comprobar "Consultar la orden $orden" "$(pedir GET "/api/ordenes/$orden" "" "$token")" 200
        grep -q '"estado":"EJECUTADA"' "$LOGS/respuesta.json" && break
        sleep 1
    done
    if ! grep -q '"cantidadEjecutada":10,"precioPromedio":5000.*"estado":"EJECUTADA"' "$LOGS/respuesta.json"; then
        echo "La orden $orden no quedó EJECUTADA con 10 acciones a 5000:"
        cat "$LOGS/respuesta.json"
        exit 1
    fi
done
# Una sola operación de la compra en trades.executed, aunque se envió dos veces
OPERACIONES=$(MSYS_NO_PATHCONV=1 docker exec bolsasim-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic trades.executed --from-beginning --timeout-ms 10000 \
    --isolation-level read_committed 2> /dev/null | grep -c "$COMPRA" || true)
if [ "$OPERACIONES" != 1 ]; then
    echo "La compra aparece en $OPERACIONES operaciones de trades.executed, no en 1"
    exit 1
fi
echo "   La venta del creador y la compra quedaron EJECUTADA, 10 acciones a 5000; una sola operación en trades.executed"
# Una por página: la más reciente (la compra) primero, y el cursor lleva a la venta
comprobar "Primera página de una orden" "$(pedir GET "/api/ordenes?tamano=1" "" "$TOKEN")" 200
grep -q "\"id\":\"$COMPRA\"" "$LOGS/respuesta.json"
SIGUIENTE=$(campo siguiente)
comprobar "Segunda página" "$(pedir GET "/api/ordenes?tamano=1&despues=$SIGUIENTE" "" "$TOKEN")" 200
grep -q "\"id\":\"$VENTA\"" "$LOGS/respuesta.json"
comprobar "Página de 101 órdenes" "$(pedir GET "/api/ordenes?tamano=101" "" "$TOKEN")" 400
comprobar "Login del segundo usuario" "$(pedir POST /api/auth/login "{\"correo\":\"otro_$SUFIJO@bolsasim.co\",\"contrasena\":\"secreta123\"}")" 200
TOKEN_OTRO=$(campo tokenAcceso)
comprobar "La orden de otro usuario" "$(pedir GET "/api/ordenes/$COMPRA" "" "$TOKEN_OTRO")" 404
comprobar "Consultar órdenes directo a order-service sin token" "$(GATEWAY=$ORDER_SERVICE pedir GET /api/ordenes)" 401
echo "   Paginación con cursor 200, página de 101 400, orden de otro 404, sin token 401"

echo "8. Comprando a precio límite (HU-05)..."
# La compra de 10 a 5000 del paso 7 ya se pagó: 50.000 menos
esperar_respuesta "Cuenta después de la compra del paso 7" /api/cuenta "$TOKEN" \
    '"saldoDisponible":9950000,"saldoReservado":0,"saldoTotal":9950000'
# El creador de mercado deja una venta de 100 a 4900 en el libro
VENTA_4900="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"VENTA\",\"tipo\":\"LIMITE\",\"precioLimite\":4900,\"cantidad\":100}"
comprobar "Venta de 100 a 4900 del creador" "$(pedir POST /api/ordenes "$VENTA_4900" "$TOKEN_CREADOR")" 202
VENTA_4900_ID=$(campo ordenId)
esperar_respuesta "Venta del creador en el libro" "/api/ordenes/$VENTA_4900_ID" "$TOKEN_CREADOR" '"estado":"ABIERTA"'
# Se reservan 500.000, se ejecuta a 4900 (el precio de la venta que esperaba) y se liberan los 10.000 sobrantes
COMPRA_5000="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":5000,\"cantidad\":100}"
comprobar "Compra de 100 a 5000" "$(pedir POST /api/ordenes "$COMPRA_5000" "$TOKEN")" 202
COMPRA_HU05=$(campo ordenId)
esperar_respuesta "Compra de 100 a 5000" "/api/ordenes/$COMPRA_HU05" "$TOKEN" \
    '"cantidadEjecutada":100,"precioPromedio":4900.*"estado":"EJECUTADA"'
esperar_respuesta "Cuenta después de la compra a 4900" /api/cuenta "$TOKEN" \
    '"saldoDisponible":9460000,"saldoReservado":0,"saldoTotal":9460000'
comprobar "Movimientos de la compra" "$(pedir GET "/api/cuenta/movimientos?tamano=3" "" "$TOKEN")" 200
grep -q "\"tipo\":\"RESERVA\",\"monto\":-500000,\"simbolo\":\"$SIMBOLO\",\"cantidad\":100,\"referenciaId\":\"$COMPRA_HU05\"" \
    "$LOGS/respuesta.json"
grep -q "\"tipo\":\"COMPRA\",\"monto\":-490000,\"simbolo\":\"$SIMBOLO\",\"cantidad\":100" "$LOGS/respuesta.json"
grep -q "\"tipo\":\"LIBERACION\",\"monto\":10000,\"simbolo\":\"$SIMBOLO\",\"cantidad\":null,\"referenciaId\":\"$COMPRA_HU05\"" \
    "$LOGS/respuesta.json"
echo "   Venta de 100 a 4900 en el libro: la compra de 100 a 5000 reservó 500.000, se ejecutó a 4900 y liberó"
echo "   10.000; saldo 9.460.000, reservado 0, movimientos RESERVA, COMPRA y LIBERACION"
# Sin contraparte: queda ABIERTA con su dinero reservado. La banda ya se movió con la operación a 4900
COMPRA_ABIERTA="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":4500,\"cantidad\":100}"
comprobar "Compra de 100 a 4500" "$(pedir POST /api/ordenes "$COMPRA_ABIERTA" "$TOKEN")" 202
ABIERTA=$(campo ordenId)
esperar_respuesta "Compra de 100 a 4500 en el libro" "/api/ordenes/$ABIERTA" "$TOKEN" '"estado":"ABIERTA"'
esperar_respuesta "Cuenta con la compra abierta" /api/cuenta "$TOKEN" \
    '"saldoDisponible":9010000,"saldoReservado":450000,"saldoTotal":9460000'
# Sin saldo: 10.000 a 4900 necesitan 49.000.000 y quedan 9.010.000. Responde 202 y la orden queda RECHAZADA
SIN_SALDO="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":4900,\"cantidad\":10000}"
comprobar "Compra sin saldo suficiente" "$(pedir POST /api/ordenes "$SIN_SALDO" "$TOKEN")" 202
RECHAZADA=$(campo ordenId)
esperar_respuesta "Compra sin saldo" "/api/ordenes/$RECHAZADA" "$TOKEN" '"estado":"RECHAZADA"'
grep -q '"motivo":"Saldo insuficiente: tienes 9.010.000 COP disponibles y la orden necesita 49.000.000 COP."' \
    "$LOGS/respuesta.json"
comprobar "Cuenta después del rechazo" "$(pedir GET /api/cuenta "" "$TOKEN")" 200
grep -q '"saldoDisponible":9010000,"saldoReservado":450000' "$LOGS/respuesta.json"
echo "   Compra sin contraparte ABIERTA con 450.000 reservados; sin saldo, RECHAZADA con \"Saldo insuficiente: tienes"
echo "   9.010.000 COP disponibles\" y la cuenta sin cambios"

echo "   Comprobando lo que cobró el creador de mercado (HU-06)..."
# Vendió 10 a 5000 y 100 a 4900: cobró los 540.000 que pagó el comprador (RNF-08)
CREADOR_ESPERADO=$((CREADOR_ANTES + 540000))
esperar_respuesta "Cuenta del creador después de vender" /api/cuenta "$TOKEN_CREADOR" \
    "\"saldoDisponible\":$CREADOR_ESPERADO,"
comprobar "Ventas del creador" "$(pedir GET "/api/cuenta/movimientos?tipo=VENTA&tamano=2" "" "$TOKEN_CREADOR")" 200
grep -q "\"tipo\":\"VENTA\",\"monto\":490000,\"simbolo\":\"$SIMBOLO\",\"cantidad\":100" "$LOGS/respuesta.json"
grep -q "\"tipo\":\"VENTA\",\"monto\":50000,\"simbolo\":\"$SIMBOLO\",\"cantidad\":10" "$LOGS/respuesta.json"
echo "   El creador cobró 540.000 (de $CREADOR_ANTES a $CREADOR_ESPERADO) y el comprador pagó lo mismo: VENTA 50.000 y"
echo "   490.000 en sus movimientos"

echo "9. Cancelando órdenes (HU-08)..."
# La compra de 100 a 4500 del paso 8 sigue en el libro con 450.000 reservados
comprobar "Cancelar la compra abierta" "$(pedir POST "/api/ordenes/$ABIERTA/cancelacion" "" "$TOKEN")" 202
grep -q '"estado":"ABIERTA"' "$LOGS/respuesta.json"
grep -q '"cancelacionSolicitadaEn":"' "$LOGS/respuesta.json"
esperar_respuesta "Compra abierta cancelada" "/api/ordenes/$ABIERTA" "$TOKEN" \
    '"cantidadEjecutada":0,.*"estado":"CANCELADA","motivo":"Cancelada por el usuario."'
# El disponible vuelve a ser el de antes de la orden: 9.460.000
esperar_respuesta "Cuenta después de cancelar" /api/cuenta "$TOKEN" \
    '"saldoDisponible":9460000,"saldoReservado":0,"saldoTotal":9460000'
comprobar "Cancelar otra vez la misma orden" "$(pedir POST "/api/ordenes/$ABIERTA/cancelacion" "" "$TOKEN")" 202
comprobar "Cancelar una orden ejecutada" "$(pedir POST "/api/ordenes/$COMPRA_HU05/cancelacion" "" "$TOKEN")" 409
grep -q 'La orden ya terminó (EJECUTADA): no se puede cancelar.' "$LOGS/respuesta.json"
comprobar "Cancelar la orden de otro usuario" "$(pedir POST "/api/ordenes/$ABIERTA/cancelacion" "" "$TOKEN_OTRO")" 404
comprobar "Cancelar sin token" "$(pedir POST "/api/ordenes/$ABIERTA/cancelacion")" 401
echo "   Compra sin contraparte cancelada: CANCELADA, 0 ejecutadas y el disponible otra vez en 9.460.000; repetir 202,"
echo "   una ejecutada 409, la de otro 404, sin token 401"

# Una compra de 100 a 4900 que solo encuentra 30 a la venta: queda PARCIAL
VENTA_30="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"VENTA\",\"tipo\":\"LIMITE\",\"precioLimite\":4900,\"cantidad\":30}"
comprobar "Venta de 30 a 4900 del creador" "$(pedir POST /api/ordenes "$VENTA_30" "$TOKEN_CREADOR")" 202
VENTA_30_ID=$(campo ordenId)
esperar_respuesta "Venta de 30 en el libro" "/api/ordenes/$VENTA_30_ID" "$TOKEN_CREADOR" '"estado":"ABIERTA"'
COMPRA_100="{\"simbolo\":\"$SIMBOLO\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":4900,\"cantidad\":100}"
comprobar "Compra de 100 a 4900" "$(pedir POST /api/ordenes "$COMPRA_100" "$TOKEN")" 202
PARCIAL=$(campo ordenId)
esperar_respuesta "Compra ejecutada en parte" "/api/ordenes/$PARCIAL" "$TOKEN" \
    '"cantidadEjecutada":30,.*"estado":"PARCIAL"'
# Reservó 490.000 y pagó 147.000: quedan 343.000 reservados para las 70 pendientes
esperar_respuesta "Cuenta con la compra parcial" /api/cuenta "$TOKEN" \
    '"saldoDisponible":8970000,"saldoReservado":343000,"saldoTotal":9313000'
comprobar "Cancelar la compra parcial" "$(pedir POST "/api/ordenes/$PARCIAL/cancelacion" "" "$TOKEN")" 202
esperar_respuesta "Compra parcial cancelada" "/api/ordenes/$PARCIAL" "$TOKEN" \
    '"cantidadEjecutada":30,"precioPromedio":4900.*"estado":"CANCELADA","motivo":"Cancelada por el usuario."'
# Solo vuelve lo de las 70 que no se compraron; las 30 compradas se pagaron
esperar_respuesta "Cuenta después de cancelar la parcial" /api/cuenta "$TOKEN" \
    '"saldoDisponible":9313000,"saldoReservado":0,"saldoTotal":9313000'
echo "   Compra de 100 ejecutada en 30 y cancelada: CANCELADA con las 30 a 4900; se devolvieron los 343.000 de las"
echo "   70 pendientes y el saldo queda en 9.313.000"

echo "10. Renovando el token y cerrando sesión..."
comprobar "Renovar el token" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 200
comprobar "Cerrar sesión sin token" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
comprobar "Cerrar sesión directo a auth-service sin token" \
    "$(GATEWAY=$AUTH_SERVICE pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
comprobar "Cerrar sesión" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}" "$TOKEN")" 204
comprobar "Renovar después de cerrar sesión" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
echo "   Refresco 200, logout sin token 401, logout 204 y refresco revocado 401"

echo "OK: todo pasó por el gateway; el usuario se registró, inició sesión, el ADMIN creó un instrumento sin reiniciar nada, el creador de mercado recibió sus acciones, las órdenes quedaron guardadas, reservaron su saldo o sus acciones, viajaron por Kafka, el motor las ejecutó, portfolio-service liquidó al comprador y al vendedor, y las cancelaciones devolvieron lo pendiente. Logs en $LOGS/"
