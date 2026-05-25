package com.example.service.impl.topup.statsbycard;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.topup.stats.YearMonthMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.method.TopupMonthMethodResponse;
import com.example.domain.responses.topup.stats.method.TopupYearlyMethodResponse;
import com.example.repository.topup.statsbycard.TopupMethodByCardRepository;
import com.example.service.topup.stats.method.TopupMethodByCardService;

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
public class TopupMethodByCardServiceImpl implements TopupMethodByCardService {
    private static final Logger logger = LoggerFactory.getLogger(TopupMethodByCardServiceImpl.class);

    private final TopupMethodByCardRepository topupMethodByCardRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    private static final long STATS_CACHE_TTL_SECONDS = 300;

    @Inject
    public TopupMethodByCardServiceImpl(TopupMethodByCardRepository topupMethodByCardRepository,
            RedisService redisService,
            ObjectMapper objectMapper,
            OpenTelemetry openTelemetry) {
        this.topupMethodByCardRepository = topupMethodByCardRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("topup-method-by-card-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("topup-method-by-card-service");

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

    private void validateCard(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            logger.error("❌ Card number is required");
            throw new IllegalArgumentException("Card number is required");
        }
    }

    private void validateYear(Long year) {
        if (year == null || year < 1 || year > 9999) {
            logger.error("❌ Invalid year: {}", year);
            throw new IllegalArgumentException("Invalid year");
        }
    }

    @Override
    public Uni<ApiResponse<List<TopupMonthMethodResponse>>> findMonthlyMethods(YearMonthMethod req) {
        String cardNumber = req.getCardNumber();
        Long year = req.getYear();
        logger.info("📊 Fetching monthly topup methods for card={} year={}", cardNumber, year);

        try {
            validateCard(cardNumber);
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("topups:stats:card:method:month:%s:%d", cardNumber, year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TopupMonthMethodResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TopupMonthMethodResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched monthly topup methods for card=" + cardNumber, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findMonthlyMethods")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "topup-method-by-card-service")
                            .setAttribute("operation", "find_monthly_methods")
                            .setAttribute("cardNumber", cardNumber)
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return topupMethodByCardRepository.findMonthlyTopupMethodsByCard(cardNumber, year)
                            .chain(methods -> {
                                List<TopupMonthMethodResponse> response = methods.stream()
                                        .map(TopupMonthMethodResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_monthly_methods",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched monthly topup methods for card=" + cardNumber,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch monthly topup methods for card={} year={}", cardNumber,
                                        year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_methods",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch monthly topup methods for card=" + cardNumber,
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_monthly_methods"));
                            });
                });
    }

    @Override
    public Uni<ApiResponse<List<TopupYearlyMethodResponse>>> findYearlyMethods(YearMonthMethod req) {
        String cardNumber = req.getCardNumber();
        Long year = req.getYear();
        logger.info("📊 Fetching yearly topup methods for card={} until year={}", cardNumber, year);

        try {
            validateCard(cardNumber);
            validateYear(year);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
        }

        String cacheKey = String.format("topups:stats:card:method:year:%s:%d", cardNumber, year);

        return redisService.getReactive(cacheKey)
                .chain(cachedJson -> {
                    if (cachedJson != null) {
                        logger.info("Cache HIT for key: {}", cacheKey);
                        List<TopupYearlyMethodResponse> cached = fromJson(cachedJson,
                                new TypeReference<List<TopupYearlyMethodResponse>>() {
                                });
                        return Uni.createFrom().item(ApiResponse
                                .success("Successfully fetched yearly topup methods for card=" + cardNumber, cached));
                    }

                    logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                    long startTime = System.currentTimeMillis();
                    Span span = tracer.spanBuilder("findYearlyMethods")
                            .setSpanKind(SpanKind.SERVER)
                            .setAttribute("service.name", "topup-method-by-card-service")
                            .setAttribute("operation", "find_yearly_methods")
                            .setAttribute("cardNumber", cardNumber)
                            .setAttribute("year", String.valueOf(year))
                            .startSpan();

                    return topupMethodByCardRepository.findYearlyTopupMethodsByCardId(cardNumber, year)
                            .chain(methods -> {
                                List<TopupYearlyMethodResponse> response = methods.stream()
                                        .map(TopupYearlyMethodResponse::from)
                                        .collect(Collectors.toList());

                                return redisService
                                        .setWithExpirationReactive(cacheKey, toJson(response), STATS_CACHE_TTL_SECONDS)
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "find_yearly_methods",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success(
                                                    "Successfully fetched yearly topup methods for card=" + cardNumber,
                                                    response);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                logger.error("💥 Failed to fetch yearly topup methods for card={} until year={}",
                                        cardNumber, year, e);
                                span.recordException(e);
                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_methods",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                                return new ApiResponse<>("error",
                                        "Failed to fetch yearly topup methods for card=" + cardNumber,
                                        Collections.emptyList());
                            })
                            .eventually(() -> {
                                span.end();
                                double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                requestDurationSeconds.record(duration, Attributes.of(
                                        AttributeKey.stringKey("operation"), "find_yearly_methods"));
                            });
                });
    }
}
