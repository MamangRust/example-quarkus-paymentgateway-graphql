package com.example.service.impl.saldo.stats;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.saldo.MonthTotalSaldoBalance;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoMonthTotalBalanceResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoYearTotalBalanceResponse;
import com.example.repository.saldo.stats.SaldoTotalAmountRepository;
import com.example.service.saldo.stats.SaldoTotalAmountService;

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
public class SaldoTotalAmountServiceImpl implements SaldoTotalAmountService {
    private static final Logger logger = LoggerFactory.getLogger(SaldoTotalAmountServiceImpl.class);

    private final SaldoTotalAmountRepository saldoTotalAmountRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public SaldoTotalAmountServiceImpl(SaldoTotalAmountRepository saldoTotalAmountRepository,
            OpenTelemetry openTelemetry,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.saldoTotalAmountRepository = saldoTotalAmountRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("saldo-total-amount-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("saldo-total-amount-service");

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

    private void validateYearMonth(Long year, Integer month) {
        if (year == null || month == null) {
            logger.error("❌ Year or month is null | year={}, month={}", year, month);
            throw new IllegalArgumentException("Year and month cannot be null");
        }

        if (year < 1900 || year > 2100) {
            logger.error("❌ Invalid year: {}", year);
            throw new IllegalArgumentException("Invalid year: " + year);
        }

        if (month < 1 || month > 12) {
            logger.error("❌ Invalid month: {}", month);
            throw new IllegalArgumentException("Invalid month: " + month);
        }
    }

    private void validateYear(Long year) {
        if (year == null || year < 1 || year > 9999) {
            logger.error("❌ Invalid year: {}", year);
            throw new IllegalArgumentException("Invalid year");
        }
    }

    @Override
    public Uni<ApiResponse<List<SaldoMonthTotalBalanceResponse>>> findMonthTotalBalance(MonthTotalSaldoBalance req) {
        try {
            validateYearMonth(req.getYear(), req.getMonth());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("saldo-stats:total-balance:monthly:%d:%d", req.getYear(), req.getMonth());

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<SaldoMonthTotalBalanceResponse>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<SaldoMonthTotalBalanceResponse>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthTotalBalance")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "saldo-total-amount-service")
                            .setAttribute("operation", "find_month_total_balance")
                            .setAttribute("year", req.getYear().toString())
                            .setAttribute("month", String.valueOf(req.getMonth()))
                            .startSpan();

                    LocalDate currentMonth = LocalDate.of(req.getYear().intValue(), req.getMonth(), 1);
                    LocalDate nextMonth = currentMonth.plusMonths(1);

                    return saldoTotalAmountRepository.findMonthlyTotalSaldoBalance(
                            req.getYear(), req.getMonth(),
                            (long) nextMonth.getYear(), nextMonth.getMonthValue())
                            .chain(monthTotals -> {
                                List<SaldoMonthTotalBalanceResponse> responseList = monthTotals.stream()
                                        .map(SaldoMonthTotalBalanceResponse::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<SaldoMonthTotalBalanceResponse>> response = ApiResponse.success(
                                        "Get monthly total balance success",
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached monthly total balance for year: {}, month: {}",
                                                    req.getYear(), req.getMonth());
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_month_total_balance",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch monthly total saldo balance for year={}, month={}",
                                        req.getYear(), req.getMonth(), e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_balance",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to get monthly total balance",
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_balance"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<SaldoYearTotalBalanceResponse>>> findYearTotalBalance(Long year) {
        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = "saldo-stats:total-balance:yearly:" + year;

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<SaldoYearTotalBalanceResponse>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<SaldoYearTotalBalanceResponse>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearTotalBalance")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "saldo-total-amount-service")
                            .setAttribute("operation", "find_year_total_balance")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    return saldoTotalAmountRepository.findYearlyTotalSaldoBalance(year)
                            .chain(yearTotals -> {
                                List<SaldoYearTotalBalanceResponse> responseList = yearTotals.stream()
                                        .map(SaldoYearTotalBalanceResponse::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<SaldoYearTotalBalanceResponse>> response = ApiResponse.success(
                                        "Get yearly total balance success",
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached yearly total balance for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_year_total_balance",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch yearly total saldo balance for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_balance",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error", "Failed to get yearly total balance",
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_balance"));
                            });
                });
    }
}
