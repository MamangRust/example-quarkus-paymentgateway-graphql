package com.example.service.impl.transaction.stats;

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
import com.example.domain.requests.transaction.stats.MonthStatusTransaction;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusSuccess;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusSuccess;
import com.example.repository.transaction.stats.TransactionStatusRepository;
import com.example.service.transaction.stats.status.TransactionStatusService;

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
public class TransactionStatusImplService implements TransactionStatusService {
        private static final Logger logger = LoggerFactory.getLogger(TransactionStatusImplService.class);

        private final TransactionStatusRepository transactionStatusRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransactionStatusImplService(TransactionStatusRepository transactionStatusRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transactionStatusRepository = transactionStatusRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transaction-status-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transaction-status-service");

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
                        throw new IllegalArgumentException("Invalid year provided");
                }
        }

        private void validateYearMonth(Long year, Integer month) {
                validateYear(year);
                if (month == null || month < 1 || month > 12) {
                        logger.error("❌ Invalid month: {}", month);
                        throw new IllegalArgumentException("Invalid month provided");
                }
        }

        @Override
        public Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthStatusSuccess(
                        MonthStatusTransaction req) {
                Long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly SUCCESS transaction status for year: {}, month: {}", year, month);

                try {
                        validateYearMonth(year, month);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:month:success:%d:%d", year, month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseMonthStatusSuccess> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<List<TransactionResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Successfully fetched monthly SUCCESS transaction status",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-status-service")
                                                        .setAttribute("operation", "find_month_status_success")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transactionStatusRepository
                                                        .findMonthTransactionStatusSuccess(year, month,
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
                                                                                                                        + " monthly SUCCESS transaction status",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly SUCCESS transaction status for year={}, month={}",
                                                                                year, month, e);
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
                                                                                "Failed to fetch monthly SUCCESS transaction status",
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
        public Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year) {
                logger.info("📊 Fetching yearly SUCCESS transaction status for year: {}", year);

                try {
                        validateYear(year);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:year:success:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Successfully fetched yearly SUCCESS transaction status",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-status-service")
                                                        .setAttribute("operation", "find_yearly_status_success")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionStatusRepository.findYearlyTransactionStatusSuccess(year)
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
                                                                                                                        + " yearly SUCCESS transaction status",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly SUCCESS transaction status for year={}",
                                                                                year,
                                                                                e);
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
                                                                                "Failed to fetch yearly SUCCESS transaction status",
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
                        MonthStatusTransaction req) {
                Long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly FAILED transaction status for year: {}, month: {}", year, month);

                try {
                        validateYearMonth(year, month);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:month:failed:%d:%d", year, month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Successfully fetched monthly FAILED transaction status",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-status-service")
                                                        .setAttribute("operation", "find_month_status_failed")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transactionStatusRepository
                                                        .findMonthTransactionStatusFailed(year, month,
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
                                                                                                                        + " monthly FAILED transaction status",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly FAILED transaction status for year={}, month={}",
                                                                                year, month, e);
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
                                                                                "Failed to fetch monthly FAILED transaction status",
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
        public Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyStatusFailed(Long year) {
                logger.info("📊 Fetching yearly FAILED transaction status for year: {}", year);

                try {
                        validateYear(year);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("transactions:stats:status:year:failed:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Successfully fetched yearly FAILED transaction status",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-status-service")
                                                        .setAttribute("operation", "find_yearly_status_failed")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transactionStatusRepository.findYearlyTransactionStatusFailed(year)
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
                                                                                                                        + " yearly FAILED transaction status",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly FAILED transaction status for year={}",
                                                                                year,
                                                                                e);
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
                                                                                "Failed to fetch yearly FAILED transaction status",
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
