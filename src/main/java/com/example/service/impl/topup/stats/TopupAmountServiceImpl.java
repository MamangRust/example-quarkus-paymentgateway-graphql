package com.example.service.impl.topup.stats;

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
import com.example.domain.responses.topup.stats.amount.TopupMonthAmountResponse;
import com.example.domain.responses.topup.stats.amount.TopupYearlyAmountResponse;
import com.example.repository.topup.stats.TopupAmountRepository;
import com.example.service.topup.stats.amount.TopupAmountService;

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
public class TopupAmountServiceImpl implements TopupAmountService {
    private static final Logger logger = LoggerFactory.getLogger(TopupAmountServiceImpl.class);

    private final TopupAmountRepository topupAmountRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public TopupAmountServiceImpl(TopupAmountRepository topupAmountRepository,
            RedisService redisService,
            ObjectMapper objectMapper,
            OpenTelemetry openTelemetry) {
        this.topupAmountRepository = topupAmountRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("topup-amount-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("topup-amount-service");

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
            throw new IllegalArgumentException("Invalid year");
        }
    }

    @Override
    public Uni<ApiResponse<List<TopupMonthAmountResponse>>> findMonthlyAmounts(Long year) {
        logger.info("📊 Fetching monthly topup amounts for year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("topups:stats:amount:month:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TopupMonthAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TopupMonthAmountResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched monthly topup amounts for year=" + year, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthlyAmounts")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "topup-amount-service")
                            .setAttribute("operation", "find_monthly_amounts")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return topupAmountRepository.findMonthlyTopupAmounts(year)
                            .chain(amounts -> {
                                List<TopupMonthAmountResponse> response = amounts.stream()
                                        .map(TopupMonthAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_monthly_amounts",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched monthly topup amounts for year=" + year,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch monthly topup amounts for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_amounts",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch monthly topup amounts for year=" + year,
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
    public Uni<ApiResponse<List<TopupYearlyAmountResponse>>> findYearlyAmounts(Long year) {
        logger.info("📊 Fetching yearly topup amounts until year={}", year);

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("topups:stats:amount:year:%d", year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TopupYearlyAmountResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TopupYearlyAmountResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched yearly topup amounts until year=" + year, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearlyAmounts")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "topup-amount-service")
                            .setAttribute("operation", "find_yearly_amounts")
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return topupAmountRepository.findYearlyTopupAmounts(year)
                            .chain(amounts -> {
                                List<TopupYearlyAmountResponse> response = amounts.stream()
                                        .map(TopupYearlyAmountResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_yearly_amounts",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched yearly topup amounts until year=" + year,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch yearly topup amounts until year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_amounts",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch yearly topup amounts until year=" + year,
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
