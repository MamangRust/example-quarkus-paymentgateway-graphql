package com.example.service.impl.merchant.statsbyid;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.merchant.statsbyid.MonthYearAmountMerchant;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseMonthlyAmount;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseYearlyAmount;
import com.example.repository.merchant.statsbyid.MerchantAmountByIdRepository;
import com.example.service.merchant.stats.amount.MerchantAmountByIdService;

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
public class MerchantAmountByIdServiceImpl implements MerchantAmountByIdService {
    private static final Logger logger = LoggerFactory.getLogger(MerchantAmountByIdServiceImpl.class);

    private final MerchantAmountByIdRepository merchantAmountByIdRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public MerchantAmountByIdServiceImpl(MerchantAmountByIdRepository merchantAmountByIdRepository,
            OpenTelemetry openTelemetry,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.merchantAmountByIdRepository = merchantAmountByIdRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("merchant-amount-by-id-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("merchant-amount-by-id-service");

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
    public Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> findMonthAmountById(MonthYearAmountMerchant req) {
        Long merchantId = req.getMerchantId();
        Long year = (long) req.getYear();

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = String.format("merchant-stats-id:amount:monthly:%d:%d", merchantId, year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<MerchantResponseMonthlyAmount>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<MerchantResponseMonthlyAmount>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthAmountById")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-amount-by-id-service")
                            .setAttribute("operation", "find_month_amount_by_id")
                            .setAttribute("merchantId", merchantId.toString())
                            .setAttribute("year", year.toString())
                            .startSpan();

                    return merchantAmountByIdRepository.findMonthlyAmountById(merchantId, year)
                            .chain(monthlyAmounts -> {
                                List<MerchantResponseMonthlyAmount> responseList = monthlyAmounts.stream()
                                        .map(MerchantResponseMonthlyAmount::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<MerchantResponseMonthlyAmount>> response = ApiResponse.success(
                                        "Successfully fetched monthly merchant amounts for year=" + year,
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached monthly merchant amounts by ID for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_month_amount_by_id",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch monthly merchant amounts for merchantId={} year={}",
                                        merchantId, year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_amount_by_id",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_month_amount_by_id"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> findYearAmountById(MonthYearAmountMerchant req) {
        Long merchantId = req.getMerchantId();
        Long year = (long) req.getYear();

        try {
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), java.util.Collections.emptyList()));
        }

        String cacheKey = String.format("merchant-stats-id:amount:yearly:%d:%d", merchantId, year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        ApiResponse<List<MerchantResponseYearlyAmount>> response = fromJson(cachedJson,
                                new TypeReference<ApiResponse<List<MerchantResponseYearlyAmount>>>() {
                                });
                        return Uni.createFrom().item(response);
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearAmountById")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "merchant-amount-by-id-service")
                            .setAttribute("operation", "find_year_amount_by_id")
                            .setAttribute("merchantId", merchantId.toString())
                            .setAttribute("year", year.toString())
                            .startSpan();

                    return merchantAmountByIdRepository.findYearlyAmountById(merchantId, year)
                            .chain(yearlyAmounts -> {
                                List<MerchantResponseYearlyAmount> responseList = yearlyAmounts.stream()
                                        .map(MerchantResponseYearlyAmount::from)
                                        .collect(Collectors.toList());

                                ApiResponse<List<MerchantResponseYearlyAmount>> response = ApiResponse.success(
                                        "Successfully fetched yearly merchant amounts for year=" + year,
                                        responseList);

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            logger.info("Cached yearly merchant amounts by ID for year: {}", year);
                                            span.setStatus(StatusCode.OK);

                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_year_amount_by_id",
                                                    AttributeKey.stringKey("status"), "success"));
                                            return response;
                                        });
                            })
                            .onFailure().invoke(e -> {
                                logger.error("❌ Failed to fetch yearly merchant amounts for merchantId={} year={}",
                                        merchantId, year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_amount_by_id",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_year_amount_by_id"));
                            });
                });
    }
}
