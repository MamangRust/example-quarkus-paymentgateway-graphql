package com.example.service.impl.card.statsbycard;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;
import com.example.repository.card.statsbycard.CardTopupAmountByCardRepository;
import com.example.service.card.statsbycard.CardTopupAmountByCardService;
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
public class CardTopupAmountByCardServiceImpl implements CardTopupAmountByCardService {
        private static final Logger logger = LoggerFactory.getLogger(CardTopupAmountByCardServiceImpl.class);

        private final CardTopupAmountByCardRepository cardTopupAmountByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 600; // 10 minutes cache

        @Inject
        public CardTopupAmountByCardServiceImpl(CardTopupAmountByCardRepository cardTopupAmountByCardRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardTopupAmountByCardRepository = cardTopupAmountByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-topup-amount-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-topup-amount-by-card-service");

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
                        logger.error("Error serializing statistics to JSON", e);
                        throw new RuntimeException("Failed to serialize statistics", e);
                }
        }

        private <T> T fromJson(String json, TypeReference<T> typeReference) {
                try {
                        return objectMapper.readValue(json, typeReference);
                } catch (JsonProcessingException e) {
                        logger.error("Error deserializing statistics from JSON", e);
                        throw new RuntimeException("Failed to deserialize statistics JSON", e);
                }
        }

        @Override
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountByCard(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-topup:" + req.getCardNumber() + ":month:" + req.getYear();

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponse<List<CardResponseMonthAmount>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponse<List<CardResponseMonthAmount>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Querying database.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthAmountByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-topup-amount-by-card-service")
                                                        .setAttribute("operation", "find_month_amount_by_card")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(req.getYear().intValue(), 1, 1);

                                        return cardTopupAmountByCardRepository
                                                        .getMonthlyTopupAmountByCard(req.getCardNumber(), yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly top-up data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly top-up stats found for card "
                                                                                                        + req.getCardNumber()
                                                                                                        + " year "
                                                                                                        + req.getYear(),
                                                                                        List.of());
                                                                        span.setStatus(StatusCode.OK);
                                                                        return Uni.createFrom().item(response);
                                                                }

                                                                List<CardResponseMonthAmount> mappedList = balances
                                                                                .stream()
                                                                                .map(CardResponseMonthAmount::from)
                                                                                .collect(Collectors.toList());

                                                                ApiResponse<List<CardResponseMonthAmount>> response = ApiResponse
                                                                                .success(
                                                                                                "Monthly top-up stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly top-up records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_month_amount_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch monthly top-up for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_amount_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_amount_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountByCard(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-topup:" + req.getCardNumber() + ":year:" + req.getYear();

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponse<List<CardResponseYearAmount>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponse<List<CardResponseYearAmount>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Querying database.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearAmountByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-topup-amount-by-card-service")
                                                        .setAttribute("operation", "find_year_amount_by_card")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        return cardTopupAmountByCardRepository
                                                        .getYearlyTopupAmountByCard(req.getCardNumber(), req.getYear())
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly top-up data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly top-up stats found for card "
                                                                                                        + req.getCardNumber()
                                                                                                        + " year "
                                                                                                        + req.getYear(),
                                                                                        List.of());
                                                                        span.setStatus(StatusCode.OK);
                                                                        return Uni.createFrom().item(response);
                                                                }

                                                                List<CardResponseYearAmount> mappedList = balances
                                                                                .stream()
                                                                                .map(CardResponseYearAmount::from)
                                                                                .collect(Collectors.toList());

                                                                ApiResponse<List<CardResponseYearAmount>> response = ApiResponse
                                                                                .success(
                                                                                                "Yearly top-up stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly top-up records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_year_amount_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch yearly top-up for card={} year={}",
                                                                                req.getCardNumber(),
                                                                                req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_amount_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_amount_by_card"));
                                                        });
                                });
        }
}
