#!/usr/bin/env bash
# Prueba de punta a punta: levanta Kafka y PostgreSQL, arranca auth-service, order-service y
# matching-engine, registra un usuario (y comprueba que llega a users.registered), envía con su id
# una venta y una compra que se cruzan y comprueba que el motor registra la ejecución a su nombre.
#
# Requisitos: Docker abierto, los jars compilados (./mvnw -B verify) y los servicios detenidos
# (la prueba arranca los suyos en los puertos 8081 y 8082).
#
#   bash scripts/prueba-integracion.sh
set -euo pipefail
cd "$(dirname "$0")/.."

LOGS=target/integracion
mkdir -p "$LOGS"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

for puerto in 8081 8082; do
    if curl -s -o /dev/null "http://localhost:$puerto"; then
        echo "El puerto $puerto está ocupado: detén order-service y auth-service antes de correr la prueba."
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

enviar_orden() {
    curl -sf -X POST http://localhost:8081/api/ordenes -H "Content-Type: application/json" -d "$1" > /dev/null
}

echo "1. Levantando Kafka y PostgreSQL..."
docker compose up -d --wait kafka postgres
docker compose up --exit-code-from kafka-init kafka-init > /dev/null

echo "2. Arrancando auth-service, matching-engine y order-service..."
"$JAVA" -jar auth-service/target/auth-service-*[0-9T].jar > "$LOGS/auth-service.log" 2>&1 &
USUARIOS=$!
"$JAVA" -jar matching-engine/target/matching-engine-*[0-9T].jar > "$LOGS/matching-engine.log" 2>&1 &
MOTOR=$!
"$JAVA" -jar order-service/target/order-service-*[0-9T].jar > "$LOGS/order-service.log" 2>&1 &
ORDENES=$!
trap 'kill $USUARIOS $MOTOR $ORDENES 2>/dev/null || true' EXIT

esperar "Started AuthServiceApplication" "$LOGS/auth-service.log" 60
esperar "Started OrderServiceApplication" "$LOGS/order-service.log" 60
esperar "partitions assigned" "$LOGS/matching-engine.log" 60

echo "3. Registrando un usuario..."
# Alias y correo distintos en cada corrida: PostgreSQL conserva los usuarios mientras el contenedor exista
SUFIJO=$(date +%s)
USUARIO=$(curl -sf -X POST http://localhost:8082/api/usuarios -H "Content-Type: application/json" -d \
    "{\"nombre\":\"Usuario de prueba\",\"alias\":\"prueba_$SUFIJO\",\"correo\":\"prueba_$SUFIJO@bolsasim.co\",
      \"contrasena\":\"secreta123\",\"aceptaPoliticaDatos\":true}")
ID=$(echo "$USUARIO" | sed -E 's/.*"id":"([^"]+)".*/\1/')
curl -sf -o /dev/null "http://localhost:8082/api/usuarios/$ID"
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

echo "4. Enviando con su id una venta y una compra de TEST a 5000..."
enviar_orden "{\"usuarioId\":\"$ID\",\"simbolo\":\"TEST\",\"lado\":\"VENTA\",\"tipo\":\"LIMITE\",\"precioLimite\":5000,\"cantidad\":10}"
enviar_orden "{\"usuarioId\":\"$ID\",\"simbolo\":\"TEST\",\"lado\":\"COMPRA\",\"tipo\":\"LIMITE\",\"precioLimite\":5000,\"cantidad\":10}"

esperar "Ejecución: 10 acciones de TEST a 5000 COP .*($ID)" "$LOGS/matching-engine.log" 30

echo "OK: el usuario se registró, sus órdenes viajaron por Kafka y el motor las ejecutó. Logs en $LOGS/"
