package com.example.service.impl.withdraw.statsbycard;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.withdraws.statsbycard.MonthStatusWithdrawCardNumber;
import com.example.domain.requests.withdraws.statsbycard.YearStatusWithdrawCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusSuccess;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusSuccess;
import com.example.repository.withdraw.statsbycard.WithdrawStatusByCardRepository;
import com.example.service.withdraw.stats.status.WithdrawStatusByCardService;

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
public class WithdrawStatusByCardImplService implements WithdrawStatusByCardService {
        private static final Logger logger = LoggerFactory.getLogger(WithdrawStatusByCardImplService.class);

        private final WithdrawStatusByCardRepository withdrawStatusByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public WithdrawStatusByCardImplService(WithdrawStatusByCardRepository withdrawStatusByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.withdrawStatusByCardRepository = withdrawStatusByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("withdraw-status-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("withdraw-status-by-card-service");

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

        private void validateYearMonth(Long year, Integer month) {
                if (year == null || year < 1 || year > 9999)
                        throw new IllegalArgumentException("Invalid year");
                if (month == null || month < 1 || month > 12)
                        throw new IllegalArgumentException("Invalid month");
        }

        private void validateYear(Long year) {
                if (year == null || year < 1 || year > 9999)
                        throw new IllegalArgumentException("Invalid year");
        }

        private void validateCardNumber(String cardNumber) {
                if (cardNumber == null || cardNumber.isBlank())
                        throw new IllegalArgumentException("Card number must not be null or blank");
        }

        @Override
        public Uni<ApiResponse<List<WithdrawResponseMonthStatusSuccess>>> findMonthStatusSuccessByCard(
                        MonthStatusWithdrawCardNumber req) {
                String cardNumber = req.getCardNumber();
                long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly SUCCESS withdraw status for card={}, year={}, month={}",
                                cardNumber, year, month);

                try {
                        validateYearMonth(year, month);
                        validateCardNumber(cardNumber);
                } catch (IllegalArgumentException e) {
                        logger.warn("⚠️ Invalid input: {}", e.getMessage());
                        return Uni.createFrom().item(new ApiResponse<>("error", "invalid input", null));
                }

                String cacheKey = String.format("withdraws:statsbycard:monthly:success:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<WithdrawResponseMonthStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<WithdrawResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly SUCCESS withdraw status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-status-by-card-service")
                                                        .setAttribute("operation", "find_month_success_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of((int) year, month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return withdrawStatusByCardRepository
                                                        .findMonthWithdrawStatusSuccessByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<WithdrawResponseMonthStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(WithdrawResponseMonthStatusSuccess::from)
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
                                                                                                                        "find_month_success_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly SUCCESS withdraw status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch monthly SUCCESS withdraw status by card",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_success_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly SUCCESS withdraw status by card",
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_success_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<WithdrawResponseYearStatusSuccess>>> findYearlyStatusSuccessByCard(
                        YearStatusWithdrawCardNumber req) {
                String cardNumber = req.getCardNumber();
                long year = req.getYear();

                logger.info("📊 Fetching yearly SUCCESS withdraw status for card={}, year={}", cardNumber, year);

                try {
                        validateYear(year);
                        validateCardNumber(cardNumber);
                } catch (IllegalArgumentException e) {
                        logger.warn("⚠️ Invalid input: {}", e.getMessage());
                        return Uni.createFrom().item(new ApiResponse<>("error", "invalid input", null));
                }

                String cacheKey = String.format("withdraws:statsbycard:yearly:success:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<WithdrawResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<WithdrawResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly SUCCESS withdraw status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-status-by-card-service")
                                                        .setAttribute("operation", "find_year_success_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return withdrawStatusByCardRepository
                                                        .findYearlyWithdrawStatusSuccessByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<WithdrawResponseYearStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(WithdrawResponseYearStatusSuccess::from)
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
                                                                                                                        "find_year_success_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly SUCCESS withdraw status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly SUCCESS withdraw status by card",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_success_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly SUCCESS withdraw status by card",
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_success_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<WithdrawResponseMonthStatusFailed>>> findMonthStatusFailedByCard(
                        MonthStatusWithdrawCardNumber req) {
                String cardNumber = req.getCardNumber();
                long year = req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly FAILED withdraw status for card={}, year={}, month={}",
                                cardNumber, year, month);

                try {
                        validateYearMonth(year, month);
                        validateCardNumber(cardNumber);
                } catch (IllegalArgumentException e) {
                        logger.warn("⚠️ Invalid input: {}", e.getMessage());
                        return Uni.createFrom().item(new ApiResponse<>("error", "invalid input", null));
                }

                String cacheKey = String.format("withdraws:statsbycard:monthly:failed:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<WithdrawResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<WithdrawResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly FAILED withdraw status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-status-by-card-service")
                                                        .setAttribute("operation", "find_month_failed_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of((int) year, month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return withdrawStatusByCardRepository
                                                        .findMonthWithdrawStatusFailedByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<WithdrawResponseMonthStatusFailed> response = results
                                                                                .stream()
                                                                                .map(WithdrawResponseMonthStatusFailed::from)
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
                                                                                                                        "find_month_failed_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly FAILED withdraw status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch monthly FAILED withdraw status by card",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_failed_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch monthly FAILED withdraw status by card",
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_failed_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<WithdrawResponseYearStatusFailed>>> findYearlyStatusFailedByCard(
                        YearStatusWithdrawCardNumber req) {
                String cardNumber = req.getCardNumber();
                long year = req.getYear();

                logger.info("📊 Fetching yearly FAILED withdraw status for card={}, year={}", cardNumber, year);

                try {
                        validateYear(year);
                        validateCardNumber(cardNumber);
                } catch (IllegalArgumentException e) {
                        logger.warn("⚠️ Invalid input: {}", e.getMessage());
                        return Uni.createFrom().item(new ApiResponse<>("error", "invalid input", null));
                }

                String cacheKey = String.format("withdraws:statsbycard:yearly:failed:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<WithdrawResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<WithdrawResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly FAILED withdraw status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-status-by-card-service")
                                                        .setAttribute("operation", "find_year_failed_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return withdrawStatusByCardRepository
                                                        .findYearlyWithdrawStatusFailedByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<WithdrawResponseYearStatusFailed> response = results
                                                                                .stream()
                                                                                .map(WithdrawResponseYearStatusFailed::from)
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
                                                                                                                        "find_year_failed_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly FAILED withdraw status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly FAILED withdraw status by card",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_failed_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch yearly FAILED withdraw status by card",
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_year_failed_by_card"));
                                                        });
                                });
        }
}
