package com.example.service.impl.transaction.stats;

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
import com.example.domain.responses.transaction.stats.amount.TransactionMonthAmountResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionYearlyAmountResponse;
import com.example.repository.transaction.stats.TransactionAmountRepository;
import com.example.service.transaction.stats.amount.TransactionAmountService;

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
public class TransactionAmountImplService implements TransactionAmountService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionAmountImplService.class);

    private final TransactionAmountRepository transactionAmountRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public TransactionAmountImplService(TransactionAmountRepository transactionAmountRepository,
            RedisService redisService,
            ObjectMapper objectMapper,
            OpenTelemetry openTelemetry) {
        this.transactionAmountRepository = transactionAmountRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("transaction-amount-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("transaction-amount-service");

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

    private void validateYear(Long year) {
        if (year == null || year < 1 || year > 9999) {
            logger.error("❌ Invalid year: {}", year);
            throw new IllegalArgumentException("Invalid year provided");
        }
    }

    @Override
    public Uni<ApiResponse<List<TransactionMonthAmountResponse>>> findMonthlyAmounts(Long year) {
        logger.info("📊 Fetching monthly transaction amounts for year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("transactions:stats:amount:month:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TransactionMonthAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TransactionMonthAmountResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched monthly transaction amounts for year=" + year, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthlyAmounts")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "transaction-amount-service")
                            .setAttribute("operation", "find_monthly_amounts")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return transactionAmountRepository.findMonthlyAmounts(year)
                            .chain(amounts -> {
                                List<TransactionMonthAmountResponse> response = amounts.stream()
                                        .map(TransactionMonthAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_monthly_amounts",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched monthly transaction amounts for year=" + year,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch monthly transaction amounts for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_amounts",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch monthly transaction amounts for year=" + year,
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_amounts"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<TransactionYearlyAmountResponse>>> findYearlyAmounts(Long year) {
        logger.info("📊 Fetching yearly transaction amounts until year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("transactions:stats:amount:year:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TransactionYearlyAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TransactionYearlyAmountResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched yearly transaction amounts until year=" + year, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearlyAmounts")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "transaction-amount-service")
                            .setAttribute("operation", "find_yearly_amounts")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return transactionAmountRepository.findYearlyAmounts(year)
                            .chain(amounts -> {
                                List<TransactionYearlyAmountResponse> response = amounts.stream()
                                        .map(TransactionYearlyAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_yearly_amounts",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched yearly transaction amounts until year="
                                                            + year,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch yearly transaction amounts until year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_amounts",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch yearly transaction amounts until year=" + year,
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_amounts"));
                            });
                });
    }
}
