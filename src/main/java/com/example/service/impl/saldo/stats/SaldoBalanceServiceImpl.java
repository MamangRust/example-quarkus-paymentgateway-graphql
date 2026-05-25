package com.example.service.impl.saldo.stats;

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
import com.example.domain.responses.saldo.stats.balance.SaldoMonthBalanceResponse;
import com.example.domain.responses.saldo.stats.balance.SaldoYearBalanceResponse;
import com.example.repository.saldo.stats.SaldoBalanceRepository;
import com.example.service.saldo.stats.SaldoBalanceService;

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
public class SaldoBalanceServiceImpl implements SaldoBalanceService {
    private static final Logger logger = LoggerFactory.getLogger(SaldoBalanceServiceImpl.class);

    private final SaldoBalanceRepository saldoBalanceRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public SaldoBalanceServiceImpl(SaldoBalanceRepository saldoBalanceRepository,
            OpenTelemetry openTelemetry,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.saldoBalanceRepository = saldoBalanceRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("saldo-balance-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("saldo-balance-service");

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
    public Uni<ApiResponse<List<SaldoMonthBalanceResponse>>> getMonthBalance(Long year) {
        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = "saldo-stats:balance:monthly:" + year;

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<SaldoMonthBalanceResponse>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<SaldoMonthBalanceResponse>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("getMonthBalance")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "saldo-balance-service")
                            .setAttribute("operation", "get_month_balance")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    return saldoBalanceRepository.findMonthlySaldoBalances(year)
                            .chain(monthBalances -> {
                                List<SaldoMonthBalanceResponse> responseList = monthBalances.stream()
                                        .map(SaldoMonthBalanceResponse::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<SaldoMonthBalanceResponse>> response = ApiResponse.success(
                                        "Successfully fetched monthly saldo balance",
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached monthly saldo balance for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "get_month_balance",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("❌ Failed to fetch monthly saldo balance for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "get_month_balance",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to fetch monthly saldo balance",
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "get_month_balance"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<SaldoYearBalanceResponse>>> getYearBalance(Long year) {
        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = "saldo-stats:balance:yearly:" + year;

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<SaldoYearBalanceResponse>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<SaldoYearBalanceResponse>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("getYearBalance")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "saldo-balance-service")
                            .setAttribute("operation", "get_year_balance")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    return saldoBalanceRepository.findYearlySaldoBalances(year)
                            .chain(yearBalances -> {
                                List<SaldoYearBalanceResponse> responseList = yearBalances.stream()
                                        .map(SaldoYearBalanceResponse::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<SaldoYearBalanceResponse>> response = ApiResponse.success(
                                        "Successfully fetched yearly saldo balance",
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached yearly saldo balance for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "get_year_balance",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("❌ Failed to fetch yearly saldo balance for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "get_year_balance",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to fetch yearly saldo balance",
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "get_year_balance"));
                            });
                });
    }
}
