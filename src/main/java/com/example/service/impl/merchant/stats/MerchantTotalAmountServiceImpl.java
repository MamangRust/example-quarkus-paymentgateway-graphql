package com.example.service.impl.merchant.stats;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;
import com.example.repository.merchant.stats.MerchantTotalAmountRepository;
import com.example.service.merchant.stats.totalamount.MerchantTotalAmountService;

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
public class MerchantTotalAmountServiceImpl implements MerchantTotalAmountService {
    private static final Logger logger = LoggerFactory.getLogger(MerchantTotalAmountServiceImpl.class);

    private final MerchantTotalAmountRepository merchantTotalAmountRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public MerchantTotalAmountServiceImpl(MerchantTotalAmountRepository merchantTotalAmountRepository,
            OpenTelemetry openTelemetry,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.merchantTotalAmountRepository = merchantTotalAmountRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("merchant-total-amount-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("merchant-total-amount-service");

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
    public Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> findMonthTotalAmount(Long year) {
        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = "merchant-stats:total-amount:monthly:" + year;

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<MerchantResponseMonthlyTotalAmount>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthTotalAmount")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-total-amount-service")
                            .setAttribute("operation", "find_month_total_amount")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    LocalDate yearDate = LocalDate.of(year.intValue(), 1, 1);

                    return merchantTotalAmountRepository.findMonthlyTotalAmount(yearDate)
                            .chain(monthlyTotals -> {
                                List<MerchantResponseMonthlyTotalAmount> responseList = monthlyTotals.stream()
                                        .map(MerchantResponseMonthlyTotalAmount::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<MerchantResponseMonthlyTotalAmount>> response = ApiResponse.success(
                                        "Successfully fetched monthly total merchant amounts for year=" + year,
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached monthly total merchant amounts for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_month_total_amount",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch monthly total merchant amounts for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_amount",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_amount"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> findYearTotalAmount(Long year) {
        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = "merchant-stats:total-amount:yearly:" + year;

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<MerchantResponseYearlyTotalAmount>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<MerchantResponseYearlyTotalAmount>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearTotalAmount")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-total-amount-service")
                            .setAttribute("operation", "find_year_total_amount")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    Long prevYear = year - 1L;

                    return merchantTotalAmountRepository.findYearlyTotalAmount(year, prevYear)
                            .chain(yearlyTotals -> {
                                List<MerchantResponseYearlyTotalAmount> responseList = yearlyTotals.stream()
                                        .map(MerchantResponseYearlyTotalAmount::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<MerchantResponseYearlyTotalAmount>> response = ApiResponse.success(
                                        "Successfully fetched yearly total merchant amounts for year=" + year,
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached yearly total merchant amounts for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_year_total_amount",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch yearly total merchant amounts for year={}", year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_amount",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_amount"));
                            });
                });
    }
}
