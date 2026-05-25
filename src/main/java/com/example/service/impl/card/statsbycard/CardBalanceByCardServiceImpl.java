package com.example.service.impl.card.statsbycard;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.balance.CardResponseMonthBalance;
import com.example.domain.responses.card.stats.balance.CardResponseYearBalance;
import com.example.repository.card.statsbycard.CardBalanceByCardRepository;
import com.example.service.card.statsbycard.CardBalanceByCardService;
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
public class CardBalanceByCardServiceImpl implements CardBalanceByCardService {
        private static final Logger logger = LoggerFactory.getLogger(CardBalanceByCardServiceImpl.class);

        private final CardBalanceByCardRepository cardBalanceByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 600; // 10 minutes cache

        @Inject
        public CardBalanceByCardServiceImpl(CardBalanceByCardRepository cardBalanceByCardRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardBalanceByCardRepository = cardBalanceByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-balance-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-balance-by-card-service");

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
        public Uni<ApiResponse<List<CardResponseMonthBalance>>> findMonthBalanceByCard(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-balance:" + req.getCardNumber() + ":month:" + req.getYear();

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponse<List<CardResponseMonthBalance>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponse<List<CardResponseMonthBalance>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Querying database.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthBalanceByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-balance-by-card-service")
                                                        .setAttribute("operation", "find_month_balance_by_card")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(req.getYear().intValue(), 1, 1);

                                        return cardBalanceByCardRepository
                                                        .getMonthlyBalancesByCard(yearDate, req.getCardNumber())
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly balance data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseMonthBalance>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly balance stats found for card "
                                                                                                        + req.getCardNumber()
                                                                                                        + " year "
                                                                                                        + req.getYear(),
                                                                                        List.of());
                                                                        span.setStatus(StatusCode.OK);
                                                                        return Uni.createFrom().item(response);
                                                                }

                                                                List<CardResponseMonthBalance> mappedList = balances
                                                                                .stream()
                                                                                .map(CardResponseMonthBalance::from)
                                                                                .collect(Collectors.toList());

                                                                ApiResponse<List<CardResponseMonthBalance>> response = ApiResponse
                                                                                .success(
                                                                                                "Monthly balance stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly balance records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_month_balance_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch monthly balance for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_balance_by_card",
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
                                                                                "find_month_balance_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseYearBalance>>> findYearBalanceByCard(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-balance:" + req.getCardNumber() + ":year:" + req.getYear();

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponse<List<CardResponseYearBalance>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponse<List<CardResponseYearBalance>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Querying database.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearBalanceByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-balance-by-card-service")
                                                        .setAttribute("operation", "find_year_balance_by_card")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        return cardBalanceByCardRepository
                                                        .getYearlyBalancesByCard(req.getYear(), req.getCardNumber())
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly balance data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseYearBalance>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly balance stats found for card "
                                                                                                        + req.getCardNumber()
                                                                                                        + " year "
                                                                                                        + req.getYear(),
                                                                                        List.of());
                                                                        span.setStatus(StatusCode.OK);
                                                                        return Uni.createFrom().item(response);
                                                                }

                                                                List<CardResponseYearBalance> mappedList = balances
                                                                                .stream()
                                                                                .map(CardResponseYearBalance::from)
                                                                                .collect(Collectors.toList());

                                                                ApiResponse<List<CardResponseYearBalance>> response = ApiResponse
                                                                                .success(
                                                                                                "Yearly balance stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly balance records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_year_balance_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch yearly balance for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_balance_by_card",
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
                                                                                "find_year_balance_by_card"));
                                                        });
                                });
        }
}
