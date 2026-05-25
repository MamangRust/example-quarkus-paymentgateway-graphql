package com.example.service.impl.card.stats;

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
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;
import com.example.repository.card.stats.CardTransferAmountRepository;
import com.example.service.card.stats.CardTransferAmountService;

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
public class CardTransferAmountServiceImpl implements CardTransferAmountService {
        private static final Logger logger = LoggerFactory.getLogger(CardTransferAmountServiceImpl.class);

        private final CardTransferAmountRepository cardTransferAmountRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 600; // 10 minutes cache

        @Inject
        public CardTransferAmountServiceImpl(CardTransferAmountRepository cardTransferAmountRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardTransferAmountRepository = cardTransferAmountRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-transfer-amount-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-transfer-amount-service");

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
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountSender(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transfer:month-sender:" + year;

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
                                                        .setAttribute("service.name", "card-transfer-amount-service")
                                                        .setAttribute("operation", "find_month_amount_sender")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(year.intValue(), 1, 1);

                                        return cardTransferAmountRepository.getMonthlyTransferAmountSender(yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly transfer sender data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly transfer sender stats found for year "
                                                                                                        + year,
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
                                                                                                "✅ Monthly transfer sender stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly transfer sender records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
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
                                                                logger.error("❌ Failed to fetch monthly transfer sender for year={}",
                                                                                year, e);
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
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountSender(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transfer:year-sender:" + year;

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
                                                        .setAttribute("service.name", "card-transfer-amount-service")
                                                        .setAttribute("operation", "find_year_amount_sender")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        return cardTransferAmountRepository.getYearlyTransferAmountSender(year)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly transfer sender data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly transfer sender stats found for year "
                                                                                                        + year,
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
                                                                                                "✅ Yearly transfer sender stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly transfer sender records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
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
                                                                logger.error("❌ Failed to fetch yearly transfer sender for year={}",
                                                                                year, e);
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
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountReceiver(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transfer:month-receiver:" + year;

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
                                                        .setAttribute("service.name", "card-transfer-amount-service")
                                                        .setAttribute("operation", "find_month_amount_receiver")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(year.intValue(), 1, 1);

                                        return cardTransferAmountRepository.getMonthlyTransferAmountReceiver(yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly transfer receiver data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly transfer receiver stats found for year "
                                                                                                        + year,
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
                                                                                                "✅ Monthly transfer receiver stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} monthly transfer receiver records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
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
                                                                logger.error("❌ Failed to fetch monthly transfer receiver for year={}",
                                                                                year, e);
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
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountReceiver(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transfer:year-receiver:" + year;

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
                                                        .setAttribute("service.name", "card-transfer-amount-service")
                                                        .setAttribute("operation", "find_year_amount_receiver")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        return cardTransferAmountRepository.getYearlyTransferAmountReceiver(year)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly transfer receiver data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly transfer receiver stats found for year "
                                                                                                        + year,
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
                                                                                                "✅ Yearly transfer receiver stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Successfully retrieved {} yearly transfer receiver records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
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
                                                                logger.error("❌ Failed to fetch yearly transfer receiver for year={}",
                                                                                year, e);
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
