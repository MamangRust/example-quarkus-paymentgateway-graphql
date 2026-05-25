package com.example.service.impl.transfer.stats;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.amount.TransferMonthAmountResponse;
import com.example.domain.responses.transfer.stats.amount.TransferYearAmountResponse;
import com.example.repository.transfer.stats.TransferAmountRepository;
import com.example.service.transfer.stats.amount.TransferAmountService;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TransferAmountImplService implements TransferAmountService {
        private static final Logger logger = LoggerFactory.getLogger(TransferAmountImplService.class);

        private final TransferAmountRepository transferAmountRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransferAmountImplService(TransferAmountRepository transferAmountRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transferAmountRepository = transferAmountRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transfer-amount-stats-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transfer-amount-stats-service");

                this.requestsTotal = meter.counterBuilder("requests_total")
                                .setDescription("Total number of requests")
                                .build();
                this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                                .setDescription("Request duration in seconds")
                                .setUnit("s")
                                .build();
        }

        private String toJson(Object obj) {
                try {
                        return objectMapper.writeValueAsString(obj);
                } catch (JsonProcessingException e) {
                        logger.error("Error serializing object to JSON", e);
                        throw new RuntimeException("Failed to serialize object", e);
                }
        }

        private <T> T fromJson(String json, TypeReference<T> typeReference) {
                try {
                        return objectMapper.readValue(json, typeReference);
                } catch (JsonProcessingException e) {
                        logger.error("Error deserializing JSON with TypeReference", e);
                        throw new RuntimeException("Failed to deserialize JSON", e);
                }
        }

        private boolean isValidYear(Long year) {
                return year != null && year >= 1 && year <= 9999;
        }

        @Override
        public Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmounts(Long year) {
                logger.info("📊 Fetching monthly transfer amounts for year={}", year);

                if (!isValidYear(year)) {
                        logger.error("❌ Invalid input year: {}", year);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid input - year", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:amount:month:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferMonthAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferMonthAmountResponse>>() {
                                                                });
                                                return Uni.createFrom()
                                                                .item(ApiResponse.success(
                                                                                "Monthly transfer amounts retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthlyTransferAmounts")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-amount-stats-service")
                                                        .setAttribute("operation", "find_monthly_amounts")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountRepository.findMonthlyTransferAmounts(year)
                                                        .chain(amounts -> {
                                                                List<TransferMonthAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferMonthAmountResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_monthly_amounts",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly transfer amounts retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch monthly transfer amounts for year={}",
                                                                                year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to retrieve monthly transfer amounts",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmounts(Long year) {
                logger.info("📊 Fetching yearly transfer amounts until year={}", year);

                if (!isValidYear(year)) {
                        logger.error("❌ Invalid input year: {}", year);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid input - year", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:amount:year:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferYearAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferYearAmountResponse>>() {
                                                                });
                                                return Uni.createFrom()
                                                                .item(ApiResponse.success(
                                                                                "Yearly transfer amounts retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyTransferAmounts")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-amount-stats-service")
                                                        .setAttribute("operation", "find_yearly_amounts")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountRepository.findYearlyTransferAmounts(year)
                                                        .chain(amounts -> {
                                                                List<TransferYearAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferYearAmountResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_yearly_amounts",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly transfer amounts retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly transfer amounts until year={}",
                                                                                year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to retrieve yearly transfer amounts",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts"));
                                                        });
                                });
        }
}
