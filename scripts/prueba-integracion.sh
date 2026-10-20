#!/usr/bin/env bash
# Prueba de punta a punta, todo a través del gateway (puerto 8080): levanta Kafka y PostgreSQL, arranca
# auth-service (con un ADMIN nuevo), order-service, market-data-service, matching-engine y el gateway,
# registra un usuario (y comprueba que llega a users.registered y a order-service), inicia sesión,
# comprueba que el perfil de otro usuario responde 404, que sin token se recibe 401 (también llamando
# directo a auth-service, order-service y market-data-service, sin el gateway) y que una orden con
# usuarioId en el cuerpo recibe 400. Después el ADMIN crea un instrumento nuevo con todo corriendo
# (un inversionista recibe 403), lo suspende (las órdenes reciben 422) y lo reactiva; con el token se
# envían una venta y una compra de ese instrumento que se cruzan, y se comprueba que sus comandos salen del
# outbox y que el motor registra la ejecución a nombre del usuario del token; la compra se reenvía con la
# misma Idempotency-Key y a orders.commands llega un solo comando. Las órdenes se consultan por id
# y en la lista paginada, y otro usuario recibe 404. Por último renueva el token y comprueba que, después de
# cerrar sesión, el token de refresco ya no sirve.
#
# Requisitos: Docker abierto, los jars compilados (./mvnw -B verify) y los servicios detenidos
# (la prueba arranca los suyos en los puertos 8080 a 8083).
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

for puerto in 8080 8081 8082 8083; do
    if curl -s -o /dev/null "http://localhost:$puerto"; then
        echo "El puerto $puerto está ocupado: detén el gateway, order-service, auth-service y market-data-service antes de correr la prueba."
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

echo "1. Levantando Kafka y PostgreSQL..."
docker compose up -d --wait kafka postgres
docker compose up --exit-code-from kafka-init kafka-init > /dev/null
# init.sql solo corre la primera vez que arranca PostgreSQL: un contenedor de antes no tiene la base "mercado"
if ! docker exec bolsasim-postgres psql -U bolsasim -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname = 'mercado'" | grep -q 1; then
    docker exec bolsasim-postgres createdb -U bolsasim mercado
    echo "   Base \"mercado\" creada"
fi

echo "2. Arrancando auth-service, market-data-service, matching-engine, order-service y el gateway..."
# Alias y correos distintos en cada corrida: PostgreSQL conserva los usuarios mientras el contenedor exista.
# El ADMIN se crea al arrancar auth-service, con una contraseña aleatoria que no queda en ninguna parte.
SUFIJO=$(date +%s)
CORREO_ADMIN="admin_$SUFIJO@bolsasim.co"
CLAVE_ADMIN="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
BOLSASIM_ADMIN_CORREO="$CORREO_ADMIN" BOLSASIM_ADMIN_CONTRASENA="$CLAVE_ADMIN" BOLSASIM_ADMIN_ALIAS="admin_$SUFIJO" \
    "$JAVA" -jar auth-service/target/auth-service-*[0-9T].jar > "$LOGS/auth-service.log" 2>&1 &
USUARIOS=$!
"$JAVA" -jar market-data-service/target/market-data-service-*[0-9T].jar > "$LOGS/market-data-service.log" 2>&1 &
MERCADO=$!
"$JAVA" -jar matching-engine/target/matching-engine-*[0-9T].jar > "$LOGS/matching-engine.log" 2>&1 &
MOTOR=$!
"$JAVA" -jar order-service/target/order-service-*[0-9T].jar > "$LOGS/order-service.log" 2>&1 &
ORDENES=$!
"$JAVA" -jar gateway/target/gateway-*[0-9T].jar > "$LOGS/gateway.log" 2>&1 &
ENTRADA=$!
trap 'kill $USUARIOS $MERCADO $MOTOR $ORDENES $ENTRADA 2>/dev/null || true' EXIT

esperar "Started AuthServiceApplication" "$LOGS/auth-service.log" 60
esperar "Administrador admin_$SUFIJO creado" "$LOGS/auth-service.log" 30
esperar "Started MarketDataServiceApplication" "$LOGS/market-data-service.log" 60
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

echo "4. Iniciando sesión..."
comprobar "Login" "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO\",\"contrasena\":\"secreta123\"}")" 200
TOKEN=$(campo tokenAcceso)
REFRESCO=$(campo tokenRefresco)
comprobar "Consultar el usuario con el token" "$(pedir GET "/api/usuarios/$ID" "" "$TOKEN")" 200
comprobar "Login con contraseña equivocada" \
    "$(pedir POST /api/auth/login "{\"correo\":\"$CORREO\",\"contrasena\":\"equivocada\"}")" 401
echo "   Login 200, consulta con token 200 y contraseña equivocada 401"

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
echo "   Sin token 401, token alterado 401, usuarioId en el cuerpo 400, instrumento inexistente 422,"
echo "   suspendido 422 hasta que se reactiva; directo a order-service también 401"

echo "7. Enviando con el token una venta y una compra de $SIMBOLO a 5000..."
comprobar "Venta" "$(pedir POST /api/ordenes "${ORDEN/COMPRA/VENTA}" "$TOKEN")" 202
VENTA=$(campo ordenId)
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

# El comando sale del outbox; el motor anota el usuario de cada orden: solo puede venir del "sub" del token
esperar "Ejecución: 10 acciones de $SIMBOLO a 5000 COP .*($ID)" "$LOGS/matching-engine.log" 30
esperar "Evento .* del outbox publicado en orders.commands (clave $SIMBOLO" "$LOGS/order-service.log" 10
echo "   Los comandos salieron del outbox y el motor ejecutó las órdenes a nombre del usuario del token"

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
for orden in "$COMPRA" "$VENTA"; do
    for _ in $(seq 1 30); do
        comprobar "Consultar la orden $orden" "$(pedir GET "/api/ordenes/$orden" "" "$TOKEN")" 200
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
echo "   La venta y la compra quedaron EJECUTADA, 10 acciones a 5000; una sola operación en trades.executed"
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

echo "8. Renovando el token y cerrando sesión..."
comprobar "Renovar el token" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 200
comprobar "Cerrar sesión sin token" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
comprobar "Cerrar sesión directo a auth-service sin token" \
    "$(GATEWAY=$AUTH_SERVICE pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
comprobar "Cerrar sesión" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}" "$TOKEN")" 204
comprobar "Renovar después de cerrar sesión" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
echo "   Refresco 200, logout sin token 401, logout 204 y refresco revocado 401"

echo "OK: todo pasó por el gateway; el usuario se registró, inició sesión, el ADMIN creó un instrumento sin reiniciar nada, las órdenes quedaron guardadas, viajaron por Kafka y el motor las ejecutó. Logs en $LOGS/"
