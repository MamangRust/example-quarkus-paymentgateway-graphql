package com.example.service.impl.transaction.statsbycard;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.transaction.stats.MonthYearPaymentMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionMonthAmountResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionYearlyAmountResponse;
import com.example.repository.transaction.statsbycard.TransactionAmountByCardRepository;
import com.example.service.transaction.stats.amount.TransactionAmountByCardService;

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
public class TransactionAmountByCardImplService implements TransactionAmountByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TransactionAmountByCardImplService.class);

        private final TransactionAmountByCardRepository transactionAmountByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransactionAmountByCardImplService(TransactionAmountByCardRepository transactionAmountByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transactionAmountByCardRepository = transactionAmountByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transaction-amount-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transaction-amount-by-card-service");

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

        private boolean isValidYear(Long year) {
                return year != null && year >= 1 && year <= 9999;
        }

        private boolean isValidCard(String cardNumber) {
                return cardNumber != null && !cardNumber.trim().isEmpty();
        }

        @Override
        public Uni<ApiResponse<List<TransactionMonthAmountResponse>>> findMonthlyAmounts(MonthYearPaymentMethod req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching monthly transaction amounts for cardNumber={}, year={}", cardNumber, year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:amount:card:month:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionMonthAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionMonthAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly transaction amounts for cardNumber retrieved successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthlyAmountsByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-amount-by-card-service")
                                                        .setAttribute("operation", "find_monthly_amounts")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionAmountByCardRepository
                                                        .findMonthlyAmountsByCard(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransactionMonthAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransactionMonthAmountResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_monthly_amounts",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly transaction amounts for cardNumber",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly transaction amounts for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly transaction amounts",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransactionYearlyAmountResponse>>> findYearlyAmounts(MonthYearPaymentMethod req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching yearly transaction amounts for cardNumber={}, until year={}", cardNumber,
                                year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:amount:card:year:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionYearlyAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionYearlyAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly transaction amounts for cardNumber retrieved successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyAmountsByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-amount-by-card-service")
                                                        .setAttribute("operation", "find_yearly_amounts")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionAmountByCardRepository
                                                        .findYearlyAmountsByCard(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransactionYearlyAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransactionYearlyAmountResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                STATS_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_yearly_amounts",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly transaction amounts for cardNumber",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly transaction amounts for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly transaction amounts",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts"));
                                                        });
                                });
        }
}
