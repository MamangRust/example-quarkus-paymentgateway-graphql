package com.example.service.impl.withdraw.stats;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawMonthlyAmountResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawYearlyAmountResponse;
import com.example.repository.withdraw.stats.WithdrawAmountRepository;
import com.example.service.withdraw.stats.amount.WithdrawAmountService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
public class WithdrawAmountImplService implements WithdrawAmountService {
    private static final Logger logger = LoggerFactory.getLogger(WithdrawAmountImplService.class);

    private final WithdrawAmountRepository withdrawAmountRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public WithdrawAmountImplService(WithdrawAmountRepository withdrawAmountRepository,
            RedisService redisService,
            ObjectMapper objectMapper,
            OpenTelemetry openTelemetry) {
        this.withdrawAmountRepository = withdrawAmountRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("withdraw-amount-stats-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("withdraw-amount-stats-service");

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
            throw new IllegalArgumentException("Invalid year");
        }
    }

    @Override
    public Uni<ApiResponse<List<WithdrawMonthlyAmountResponse>>> findMonthlyWithdraws(Long year) {
        logger.info("📊 Fetching monthly withdraw amounts for year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            logger.warn("⚠️ Invalid input year={}", year);
            return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", null));
        }

        String cacheKey = String.format("withdraws:stats:monthly:amount:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<WithdrawMonthlyAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<WithdrawMonthlyAmountResponse>>() {
                                });
                        return Uni.createFrom()
                                .item(ApiResponse.success("Monthly withdraw amounts retrieved successfully!", cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthlyWithdrawsAmount")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "withdraw-amount-stats-service")
                            .setAttribute("operation", "find_monthly_amount")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return withdrawAmountRepository.findMonthlyWithdraws(year)
                            .chain(amounts -> {
                                List<WithdrawMonthlyAmountResponse> response = amounts.stream()
                                        .map(WithdrawMonthlyAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_monthly_amount",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Monthly withdraw amounts retrieved successfully!", response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("❌ Failed to fetch monthly withdraw amounts for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_amount",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to retrieve monthly withdraw amounts", null);
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_amount"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<WithdrawYearlyAmountResponse>>> findYearlyWithdraws(Long year) {
        logger.info("📊 Fetching yearly withdraw amounts until year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            logger.warn("⚠️ Invalid input year={}", year);
            return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", null));
        }

        String cacheKey = String.format("withdraws:stats:yearly:amount:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<WithdrawYearlyAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<WithdrawYearlyAmountResponse>>() {
                                });
                        return Uni.createFrom()
                                .item(ApiResponse.success("Yearly withdraw amounts retrieved successfully!", cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearlyWithdrawsAmount")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "withdraw-amount-stats-service")
                            .setAttribute("operation", "find_yearly_amount")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return withdrawAmountRepository.findYearlyWithdraws(year)
                            .chain(amounts -> {
                                List<WithdrawYearlyAmountResponse> response = amounts.stream()
                                        .map(WithdrawYearlyAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_yearly_amount",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Yearly withdraw amounts retrieved successfully!", response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("❌ Failed to fetch yearly withdraw amounts until year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_amount",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to retrieve yearly withdraw amounts", null);
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_amount"));
                            });
                });
    }
}
