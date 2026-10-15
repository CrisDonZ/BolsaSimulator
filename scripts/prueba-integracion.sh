#!/usr/bin/env bash
# Prueba de punta a punta: levanta Kafka, arranca order-service y matching-engine,
# envía una venta y una compra que se cruzan y comprueba que el motor registra la ejecución.
#
# Requisitos: Docker abierto, los jars compilados (./mvnw -B verify) y los servicios detenidos
# (la prueba arranca los suyos en el puerto 8081).
#
#   bash scripts/prueba-integracion.sh
set -euo pipefail
cd "$(dirname "$0")/.."

LOGS=target/integracion
mkdir -p "$LOGS"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if curl -s -o /dev/null http://localhost:8081; then
    echo "El puerto 8081 está ocupado: detén order-service antes de correr la prueba."
    exit 1
fi

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

enviar() {
    curl -sf -X POST http://localhost:8081/api/ordenes -H "Content-Type: application/json" -d "$1" > /dev/null
}

echo "1. Levantando Kafka..."
docker compose up -d --wait kafka
docker compose up --exit-code-from kafka-init kafka-init > /dev/null

echo "2. Arrancando matching-engine y order-service..."
"$JAVA" -jar matching-engine/target/matching-engine-*[0-9T].jar > "$LOGS/matching-engine.log" 2>&1 &
MOTOR=$!
"$JAVA" -jar order-service/target/order-service-*[0-9T].jar > "$LOGS/order-service.log" 2>&1 &
ORDENES=$!
trap 'kill $MOTOR $ORDENES 2>/dev/null || true' EXIT

esperar "Started OrderServiceApplication" "$LOGS/order-service.log" 60
esperar "partitions assigned" "$LOGS/matching-engine.log" 60

echo "3. Enviando una venta y una compra de TEST a 5000..."
enviar '{"usuarioId":"prueba","simbolo":"TEST","lado":"VENTA","tipo":"LIMITE","precioLimite":5000,"cantidad":10}'
enviar '{"usuarioId":"prueba","simbolo":"TEST","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":10}'

esperar "Ejecución: 10 acciones de TEST a 5000 COP" "$LOGS/matching-engine.log" 30

echo "OK: la orden viajó por Kafka y el motor la ejecutó. Logs en $LOGS/"
