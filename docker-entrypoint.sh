#!/bin/sh

set -e

# Default Java options for Quarkus in containers
JAVA_OPTS="${JAVA_OPTS:-}"

# Add Quarkus-specific JVM options
QUARKUS_OPTS="-XX:+UseContainerSupport \
-XX:MaxRAMPercentage=75 \
-XX:+UseG1GC \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp/heapdump.hprof \
-Djava.security.egd=file:/dev/./urandom \
-Dquarkus.http.host=0.0.0.0"


echo "Starting Quarkus application with OpenTelemetry..."
echo "Service: ${OTEL_SERVICE_NAME:-example-quarkus-opentelemetry}"
echo "OTLP Endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:-http://otel-collector:4317}"
echo "Profile: ${QUARKUS_PROFILE:-dev}"

exec java \
  -Dquarkus.profile=${QUARKUS_PROFILE} \
  ${JAVA_OPTS} \
  -jar quarkus-run.jard