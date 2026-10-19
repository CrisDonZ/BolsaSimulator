#!/usr/bin/env bash
# Prueba de punta a punta, todo a través del gateway (puerto 8080): levanta Kafka y PostgreSQL, arranca
# auth-service, order-service, matching-engine y el gateway, registra un usuario (y comprueba que llega a
# users.registered y a order-service), inicia sesión, comprueba que sin token se recibe 401 (también
# llamando directo a order-service) y que una orden con usuarioId en el cuerpo recibe 400, envía con el
# token una venta y una compra que se cruzan y comprueba que el motor registra la ejecución a nombre del
# usuario del token. Por último renueva el token y comprueba que, después de cerrar sesión, el token de
# refresco ya no sirve.
#
# Requisitos: Docker abierto, los jars compilados (./mvnw -B verify) y los servicios detenidos
# (la prueba arranca los suyos en los puertos 8080, 8081 y 8082).
#
#   bash scripts/prueba-integracion.sh
set -euo pipefail
cd "$(dirname "$0")/.."

LOGS=target/integracion
mkdir -p "$LOGS"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
GATEWAY=http://localhost:8080
ORDER_SERVICE=http://localhost:8081

for puerto in 8080 8081 8082; do
    if curl -s -o /dev/null "http://localhost:$puerto"; then
        echo "El puerto $puerto está ocupado: detén el gateway, order-service y auth-service antes de correr la prueba."
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

# Hace una petición al gateway y devuelve el código HTTP; el cuerpo queda en $LOGS/respuesta.json.
# Uso: pedir MÉTODO RUTA [CUERPO] [TOKEN]
pedir() {
    local metodo=$1 ruta=$2 cuerpo=${3:-} token=${4:-}
    local argumentos=(-s -o "$LOGS/respuesta.json" -w "%{http_code}" -X "$metodo" "$GATEWAY$ruta")
    if [ -n "$cuerpo" ]; then
        argumentos+=(-H "Content-Type: application/json" -d "$cuerpo")
    fi
    if [ -n "$token" ]; then
        argumentos+=(-H "Authorization: Bearer $token")
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

echo "2. Arrancando auth-service, matching-engine, order-service y el gateway..."
"$JAVA" -jar auth-service/target/auth-service-*[0-9T].jar > "$LOGS/auth-service.log" 2>&1 &
USUARIOS=$!
"$JAVA" -jar matching-engine/target/matching-engine-*[0-9T].jar > "$LOGS/matching-engine.log" 2>&1 &
MOTOR=$!
"$JAVA" -jar order-service/target/order-service-*[0-9T].jar > "$LOGS/order-service.log" 2>&1 &
ORDENES=$!
"$JAVA" -jar gateway/target/gateway-*[0-9T].jar > "$LOGS/gateway.log" 2>&1 &
ENTRADA=$!
trap 'kill $USUARIOS $MOTOR $ORDENES $ENTRADA 2>/dev/null || true' EXIT

esperar "Started AuthServiceApplication" "$LOGS/auth-service.log" 60
esperar "Started OrderServiceApplication" "$LOGS/order-service.log" 60
esperar "Started GatewayApplication" "$LOGS/gateway.log" 60
esperar "partitions assigned" "$LOGS/matching-engine.log" 60

echo "3. Registrando un usuario a través del gateway..."
# Alias y correo distintos en cada corrida: PostgreSQL conserva los usuarios mientras el contenedor exista
SUFIJO=$(date +%s)
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

echo "5. Comprobando las órdenes rechazadas..."
ORDEN='{"simbolo":"TEST","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":10}'
comprobar "Orden sin token" "$(pedir POST /api/ordenes "$ORDEN")" 401
comprobar "Orden con un token alterado" "$(pedir POST /api/ordenes "$ORDEN" "${TOKEN}x")" 401
comprobar "Orden a nombre de otro usuario" "$(pedir POST /api/ordenes \
    '{"usuarioId":"00000000-0000-0000-0000-000000000000","simbolo":"TEST","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":10}' \
    "$TOKEN")" 400
# Saltándose el gateway: order-service valida el token por su cuenta
comprobar "Orden directa a order-service sin token" "$(GATEWAY=$ORDER_SERVICE pedir POST /api/ordenes "$ORDEN")" 401
comprobar "Orden directa a order-service con un token alterado" \
    "$(GATEWAY=$ORDER_SERVICE pedir POST /api/ordenes "$ORDEN" "${TOKEN}x")" 401
echo "   Sin token 401, token alterado 401 y usuarioId en el cuerpo 400; directo a order-service también 401"

echo "6. Enviando con el token una venta y una compra de TEST a 5000..."
comprobar "Venta" "$(pedir POST /api/ordenes "${ORDEN/COMPRA/VENTA}" "$TOKEN")" 202
comprobar "Compra" "$(pedir POST /api/ordenes "$ORDEN" "$TOKEN")" 202

# El motor anota el usuario de cada orden: el id del registro solo puede venir del "sub" del token
esperar "Ejecución: 10 acciones de TEST a 5000 COP .*($ID)" "$LOGS/matching-engine.log" 30
echo "   El motor ejecutó las órdenes a nombre del usuario del token"

echo "7. Renovando el token y cerrando sesión..."
comprobar "Renovar el token" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 200
comprobar "Cerrar sesión sin token" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
comprobar "Cerrar sesión" "$(pedir POST /api/auth/logout "{\"tokenRefresco\":\"$REFRESCO\"}" "$TOKEN")" 204
comprobar "Renovar después de cerrar sesión" "$(pedir POST /api/auth/refresh "{\"tokenRefresco\":\"$REFRESCO\"}")" 401
echo "   Refresco 200, logout sin token 401, logout 204 y refresco revocado 401"

echo "OK: todo pasó por el gateway; el usuario se registró, inició sesión, sus órdenes viajaron por Kafka y el motor las ejecutó. Logs en $LOGS/"
