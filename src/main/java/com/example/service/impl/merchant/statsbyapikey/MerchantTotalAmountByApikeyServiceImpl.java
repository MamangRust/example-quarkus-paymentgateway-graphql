package com.example.service.impl.merchant.statsbyapikey;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.merchant.statsbyapikey.MonthYearTotalAmountApiKey;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;
import com.example.repository.merchant.statsbyapikey.MerchantTotalAmountByApiKeyRepository;
import com.example.service.merchant.stats.totalamount.MerchantTotalAmountByApiKeyService;

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
public class MerchantTotalAmountByApikeyServiceImpl implements MerchantTotalAmountByApiKeyService {
    private static final Logger logger = LoggerFactory.getLogger(MerchantTotalAmountByApikeyServiceImpl.class);

    private final MerchantTotalAmountByApiKeyRepository merchantTotalAmountByApiKeyRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public MerchantTotalAmountByApikeyServiceImpl(
            MerchantTotalAmountByApiKeyRepository merchantTotalAmountByApiKeyRepository,
            OpenTelemetry openTelemetry,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.merchantTotalAmountByApiKeyRepository = merchantTotalAmountByApiKeyRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("merchant-total-amount-by-apikey-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("merchant-total-amount-by-apikey-service");

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
    public Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> findMonthTotalAmountByApiKey(
            MonthYearTotalAmountApiKey req) {
        String apiKey = req.getApiKey();
        Long year = (long) req.getYear();

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = String.format("merchant-stats-apikey:total-amount:monthly:%s:%d", apiKey, year);

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
                    Span span = tracer.spanBuilder("findMonthTotalAmountByApiKey")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-total-amount-by-apikey-service")
                            .setAttribute("operation", "find_month_total_amount_by_apikey")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    LocalDate yearDate = LocalDate.of(year.intValue(), 1, 1);

                    return merchantTotalAmountByApiKeyRepository.findMonthlyTotalAmountByApiKey(apiKey, yearDate)
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
                                            logger.info("Cached monthly total merchant amounts by apiKey for year: {}",
                                                    year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"),
                                                    "find_month_total_amount_by_apikey",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch monthly total merchant amounts for year={} apiKey={}",
                                        year, apiKey, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_amount_by_apikey",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_total_amount_by_apikey"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> findYearTotalAmountByApiKey(
            MonthYearTotalAmountApiKey req) {
        String apiKey = req.getApiKey();
        Long year = (long) req.getYear();

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = String.format("merchant-stats-apikey:total-amount:yearly:%s:%d", apiKey, year);

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
                    Span span = tracer.spanBuilder("findYearTotalAmountByApiKey")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-total-amount-by-apikey-service")
                            .setAttribute("operation", "find_year_total_amount_by_apikey")
                            .setAttribute("year", year.toString())
                            .startSpan();

                    Long prevYear = year - 1L;

                    return merchantTotalAmountByApiKeyRepository.findYearlyTotalAmountByApiKey(apiKey, year, prevYear)
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
                                            logger.info("Cached yearly total merchant amounts by apiKey for year: {}",
                                                    year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"),
                                                    "find_year_total_amount_by_apikey",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch yearly total merchant amounts for year={} apiKey={}",
                                        year, apiKey, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_amount_by_apikey",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_total_amount_by_apikey"));
                            });
                });
    }
}
