package com.example.service.impl.transaction.statsbycard;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.transaction.statsbycard.MonthStatusTransactionCardNumber;
import com.example.domain.requests.transaction.statsbycard.YearStatusTransactionCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusSuccess;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusSuccess;
import com.example.repository.transaction.statsbycard.TransactionStatusByCardRepository;
import com.example.service.transaction.stats.status.TransactionStatusByCardService;

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
public class TransactionStatusByCardImplService implements TransactionStatusByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TransactionStatusByCardImplService.class);

        private final TransactionStatusByCardRepository transactionStatusByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransactionStatusByCardImplService(TransactionStatusByCardRepository transactionStatusByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transactionStatusByCardRepository = transactionStatusByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transaction-status-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transaction-status-by-card-service");

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

        private boolean isValidYearMonth(Long year, Integer month) {
                return isValidYear(year) && month != null && month >= 1 && month <= 12;
        }

        @Override
        public Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthStatusSuccess(
                        MonthStatusTransactionCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly SUCCESS transactions for cardNumber={}, year={}, month={}", cardNumber,
                                year,
                                month);

                if (!isValidYearMonth(year, month) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, month={}, cardNumber={}", year, month, cardNumber);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid input - year, month, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:card:month:success:%s:%d:%d", cardNumber,
                                year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseMonthStatusSuccess> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<List<TransactionResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Found " + cached.size()
                                                                                + " monthly SUCCESS transaction records",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-status-by-card-service")
                                                        .setAttribute("operation", "find_month_status_success")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transactionStatusByCardRepository
                                                        .findMonthTransactionStatusSuccessByCard(cardNumber, year,
                                                                        month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<TransactionResponseMonthStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(TransactionResponseMonthStatusSuccess::from)
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
                                                                                                                        "find_month_status_success",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found " + response
                                                                                                                        .size()
                                                                                                                        + " monthly SUCCESS transaction records",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly SUCCESS transactions for cardNumber={}, year={}, month={}",
                                                                                cardNumber, year, month, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_success",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly SUCCESS transactions",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_success"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyStatusSuccess(
                        YearStatusTransactionCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching yearly SUCCESS transactions for cardNumber={}, year={}", cardNumber, year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:card:year:success:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Found " + cached.size()
                                                                                + " yearly SUCCESS transaction records",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-status-by-card-service")
                                                        .setAttribute("operation", "find_yearly_status_success")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionStatusByCardRepository
                                                        .findYearlyTransactionStatusSuccessByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<TransactionResponseYearStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(TransactionResponseYearStatusSuccess::from)
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
                                                                                                                        "find_yearly_status_success",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found " + response
                                                                                                                        .size()
                                                                                                                        + " yearly SUCCESS transaction records",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch yearly SUCCESS transactions for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_success",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly SUCCESS transactions",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_success"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransactionResponseMonthStatusFailed>>> findMonthStatusFailed(
                        MonthStatusTransactionCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly FAILED transactions for cardNumber={}, year={}, month={}", cardNumber,
                                year,
                                month);

                if (!isValidYearMonth(year, month) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, month={}, cardNumber={}", year, month, cardNumber);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid input - year, month, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:card:month:failed:%s:%d:%d", cardNumber,
                                year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Found " + cached.size()
                                                                                + " monthly FAILED transaction records",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-status-by-card-service")
                                                        .setAttribute("operation", "find_month_status_failed")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transactionStatusByCardRepository
                                                        .findMonthTransactionStatusFailedByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<TransactionResponseMonthStatusFailed> response = results
                                                                                .stream()
                                                                                .map(TransactionResponseMonthStatusFailed::from)
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
                                                                                                                        "find_month_status_failed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found " + response
                                                                                                                        .size()
                                                                                                                        + " monthly FAILED transaction records",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly FAILED transactions for cardNumber={}, year={}, month={}",
                                                                                cardNumber, year, month, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_failed",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly FAILED transactions",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_failed"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyStatusFailed(
                        YearStatusTransactionCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();

                logger.info("📊 Fetching yearly FAILED transactions for cardNumber={}, year={}", cardNumber, year);

                if (!isValidYear(year) || !isValidCard(cardNumber)) {
                        logger.error("❌ Invalid input: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input - year, cardNumber",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:card:year:failed:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Found " + cached.size()
                                                                                + " yearly FAILED transaction records",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transaction-status-by-card-service")
                                                        .setAttribute("operation", "find_yearly_status_failed")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionStatusByCardRepository
                                                        .findYearlyTransactionStatusFailedByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<TransactionResponseYearStatusFailed> response = results
                                                                                .stream()
                                                                                .map(TransactionResponseYearStatusFailed::from)
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
                                                                                                                        "find_yearly_status_failed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found " + response
                                                                                                                        .size()
                                                                                                                        + " yearly FAILED transaction records",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly FAILED transactions for cardNumber={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_failed",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly FAILED transactions",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_failed"));
                                                        });
                                });
        }
}
