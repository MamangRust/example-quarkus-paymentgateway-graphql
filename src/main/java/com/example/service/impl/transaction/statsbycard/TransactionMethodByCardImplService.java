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
import com.example.domain.responses.transaction.stats.method.TransactionMonthMethodResponse;
import com.example.domain.responses.transaction.stats.method.TransactionYearMethodResponse;
import com.example.repository.transaction.statsbycard.TransactionMethodByCardRepository;
import com.example.service.transaction.stats.method.TransactionMethodByCardService;

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
public class TransactionMethodByCardImplService implements TransactionMethodByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TransactionMethodByCardImplService.class);

        private final TransactionMethodByCardRepository transactionMethodByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransactionMethodByCardImplService(TransactionMethodByCardRepository transactionMethodByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transactionMethodByCardRepository = transactionMethodByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transaction-method-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transaction-method-by-card-service");

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
        public Uni<ApiResponse<List<TransactionMonthMethodResponse>>> findMonthlyMethod(MonthYearPaymentMethod req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching monthly transaction methods for cardNumber={}, year={}", cardNumber, year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:method:card:month:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionMonthMethodResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionMonthMethodResponse>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Monthly transaction methods retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthlyMethodByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-method-by-card-service")
                                                        .setAttribute("operation", "find_monthly_method")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionMethodByCardRepository
                                                        .findMonthlyPaymentMethodsByCard(cardNumber, year)
                                                        .chain(methods -> {
                                                                List<TransactionMonthMethodResponse> response = methods
                                                                                .stream()
                                                                                .map(TransactionMonthMethodResponse::from)
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
                                                                                                                        "find_monthly_method",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly Transactio methods retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly transaction methods for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_method",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly transaction methods",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_method"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransactionYearMethodResponse>>> findYearlyMethod(MonthYearPaymentMethod req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching yearly transaction methods for cardNumber={}, until year={}", cardNumber,
                                year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:method:card:year:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionYearMethodResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionYearMethodResponse>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Yearly transaction methods retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyMethodByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-method-by-card-service")
                                                        .setAttribute("operation", "find_yearly_method")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionMethodByCardRepository
                                                        .findYearlyPaymentMethodsByCard(cardNumber, year)
                                                        .chain(methods -> {
                                                                List<TransactionYearMethodResponse> response = methods
                                                                                .stream()
                                                                                .map(TransactionYearMethodResponse::from)
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
                                                                                                                        "find_yearly_method",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly Transactio methods retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly transaction methods for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_method",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly transaction methods for cardNumber",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_method"));
                                                        });
                                });
        }
}
