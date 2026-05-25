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
import com.example.repository.card.stats.CardTransactionAmountRepository;
import com.example.service.card.stats.CardTransactionAmountService;

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
public class CardTransactionAmountServiceImpl implements CardTransactionAmountService {
        private static final Logger logger = LoggerFactory.getLogger(CardTransactionAmountServiceImpl.class);

        private final CardTransactionAmountRepository cardTransactionAmountRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 600; // 10 minutes cache

        @Inject
        public CardTransactionAmountServiceImpl(CardTransactionAmountRepository cardTransactionAmountRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardTransactionAmountRepository = cardTransactionAmountRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-transaction-amount-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-transaction-amount-service");

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
        public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmount(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transaction:month:" + year;

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
                                        Span span = tracer.spanBuilder("findMonthAmount")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-transaction-amount-service")
                                                        .setAttribute("operation", "find_month_amount")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        LocalDate yearDate = LocalDate.of(year.intValue(), 1, 1);

                                        return cardTransactionAmountRepository.getMonthlyTransactionAmount(yearDate)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No monthly transaction data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseMonthAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No monthly transaction stats found for year "
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
                                                                                                "✅ Monthly transaction stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info(
                                                                                                        "Successfully retrieved {} monthly transaction records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_month_amount",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch monthly transaction for year={}",
                                                                                year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_amount",
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
                                                                                "find_month_amount"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmount(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year provided: {}", year);
                        return Uni.createFrom().item(new ApiResponse<>("error", "Invalid year provided", List.of()));
                }

                String cacheKey = "card-transaction:year:" + year;

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
                                        Span span = tracer.spanBuilder("findYearAmount")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-transaction-amount-service")
                                                        .setAttribute("operation", "find_year_amount")
                                                        .setAttribute("stats.year", year)
                                                        .startSpan();

                                        return cardTransactionAmountRepository.getYearlyTransactionAmount(year)
                                                        .chain(balances -> {
                                                                if (balances.isEmpty()) {
                                                                        logger.warn("⚠️ No yearly transaction data found for year={}",
                                                                                        year);
                                                                        ApiResponse<List<CardResponseYearAmount>> response = new ApiResponse<>(
                                                                                        "error",
                                                                                        "No yearly transaction stats found for year "
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
                                                                                                "✅ Yearly transaction stats retrieved successfully!",
                                                                                                mappedList);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info(
                                                                                                        "Successfully retrieved {} yearly transaction records for year={}",
                                                                                                        balances.size(),
                                                                                                        year);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_year_amount",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("❌ Failed to fetch yearly transaction for year={}",
                                                                                year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_amount",
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
                                                                                "find_year_amount"));
                                                        });
                                });
        }
}
