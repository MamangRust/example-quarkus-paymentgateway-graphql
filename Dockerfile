FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="example-quarkus-opentelemetry"
LABEL version="1.0.0"
LABEL description="Example Quarkus application with OpenTelemetry"

RUN addgroup -S app && adduser -S app -G app && \
    apk add --no-cache curl

WORKDIR /app


COPY --from=build /app/target/quarkus-app/quarkus/ ./quarkus/
COPY --from=build /app/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/target/quarkus-app/app/ ./app/
COPY --from=build /app/target/quarkus-app/quarkus-run.jar ./
COPY --from=build /app/target/classes/*.pem ./


COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN chmod +x /usr/local/bin/docker-entrypoint.sh && \
    chown -R app:app /app

USER app

EXPOSE 8080

ENV QUARKUS_PROFILE=docker
ENV QUARKUS_OTEL_SERVICE_NAME=example-quarkus-opentelemetry
ENV QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
ENV QUARKUS_OTEL_TRACES_EXPORTER=otlp
ENV QUARKUS_OTEL_METRICS_EXPORTER=otlp
ENV QUARKUS_OTEL_LOGS_EXPORTER=otlp

ENTRYPOINT ["docker-entrypoint.sh"]
