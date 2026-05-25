#!/bin/sh
set -e

STARTUP_DELAY=${STARTUP_DELAY:-30}

echo "========================================="
echo "Quarkus Native Bootstrap"
echo "========================================="
echo "Profile                : ${QUARKUS_PROFILE:-default}"
echo "Startup delay (seconds): ${STARTUP_DELAY}"
echo "========================================="

echo "Waiting ${STARTUP_DELAY}s before startup..."
sleep "${STARTUP_DELAY}"

echo "========================================="
echo "Starting Quarkus Native Application"
echo "========================================="
echo "OTEL Service Name  : ${OTEL_SERVICE_NAME}"
echo "OTEL OTLP Endpoint : ${OTEL_EXPORTER_OTLP_ENDPOINT}"
echo "========================================="

exec ./application "$@"
