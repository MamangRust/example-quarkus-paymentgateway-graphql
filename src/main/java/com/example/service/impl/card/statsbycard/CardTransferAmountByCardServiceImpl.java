package com.example.service.impl.card.statsbycard;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;
import com.example.repository.card.statsbycard.CardTransferAmountByCardRepository;
import com.example.service.card.statsbycard.CardTransferAmountByCardService;

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
public class CardTransferAmountByCardServiceImpl implements CardTransferAmountByCardService {
        private static final Logger logger = LoggerFactory.getLogger(CardTransferAmountByCardServiceImpl.class);

        private final CardTransferAmountByCardRepository cardTransferAmountByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 600; // 10 minutes cache

        @Inject
        public CardTransferAmountByCardServiceImpl(
                        CardTransferAmountByCardRepository cardTransferAmountByCardRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardTransferAmountByCardRepository = cardTransferAmountByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-transfer-amount-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-transfer-amount-by-card-service");

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
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountSender(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-transfer:" + req.getCardNumber() + ":month-sender:" + req.getYear();

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
                                        Span span = tracer.spanBuilder("findMonthAmountSender")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-transfer-amount-by-card-service")
                                                        .setAttribute("operation", "find_month_amount_sender")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(req.getYear().intValue(), 1, 1);

                                        return cardTransferAmountByCardRepository
                                                        .getMonthlyTransferAmountBySender(req.getCardNumber(), yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly transfer sender data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly transfer sender stats found for card "
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
                                                                                                "Monthly transfer sender stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly transfer sender records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_month_amount_sender",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch monthly transfer sender for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_amount_sender",
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
                                                                                "find_month_amount_sender"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountSender(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-transfer:" + req.getCardNumber() + ":year-sender:" + req.getYear();

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
                                        Span span = tracer.spanBuilder("findYearAmountSender")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-transfer-amount-by-card-service")
                                                        .setAttribute("operation", "find_year_amount_sender")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        return cardTransferAmountByCardRepository
                                                        .getYearlyTransferAmountBySender(req.getCardNumber(),
                                                                        req.getYear())
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly transfer sender data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly transfer sender stats found for card "
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
                                                                                                "Yearly transfer sender stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly transfer sender records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_year_amount_sender",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch yearly transfer sender for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_amount_sender",
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
                                                                                "find_year_amount_sender"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountReceiver(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-transfer:" + req.getCardNumber() + ":month-receiver:" + req.getYear();

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
                                        Span span = tracer.spanBuilder("findMonthAmountReceiver")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-transfer-amount-by-card-service")
                                                        .setAttribute("operation", "find_month_amount_receiver")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(req.getYear().intValue(), 1, 1);

                                        return cardTransferAmountByCardRepository
                                                        .getMonthlyTransferAmountByReceiver(req.getCardNumber(),
                                                                        yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly transfer receiver data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly transfer receiver stats found for card "
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
                                                                                                "Monthly transfer receiver stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly transfer receiver records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_month_amount_receiver",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch monthly transfer receiver for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_amount_receiver",
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
                                                                                "find_month_amount_receiver"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountReceiver(MonthYearCardNumberCard req) {
                if (req == null || req.getCardNumber() == null || req.getYear() == null || req.getYear() < 1
                                || req.getYear() > 9999) {
                        logger.error("❌ Invalid request parameters");
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid request parameters", List.of()));
                }

                String cacheKey = "card-transfer:" + req.getCardNumber() + ":year-receiver:" + req.getYear();

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
                                        Span span = tracer.spanBuilder("findYearAmountReceiver")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "card-transfer-amount-by-card-service")
                                                        .setAttribute("operation", "find_year_amount_receiver")
                                                        .setAttribute("stats.year", req.getYear())
                                                        .setAttribute("stats.card", req.getCardNumber())
                                                        .startSpan();

                                        return cardTransferAmountByCardRepository
                                                        .getYearlyTransferAmountByReceiver(req.getCardNumber(),
                                                                        req.getYear())
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly transfer receiver data found for card={} year={}",
                                                                                        req.getCardNumber(),
                                                                                        req.getYear());
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly transfer receiver stats found for card "
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
                                                                                                "Yearly transfer receiver stats retrieved successfully",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly transfer receiver records for card={}",
                                                                                                        balances.size(),
                                                                                                        req.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_year_amount_receiver",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch yearly transfer receiver for card={} year={}",
                                                                                req.getCardNumber(), req.getYear(), e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_amount_receiver",
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
                                                                                "find_year_amount_receiver"));
                                                        });
                                });
        }
}
