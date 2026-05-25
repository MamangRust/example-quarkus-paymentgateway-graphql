package com.example.service.impl.topup.statsbycard;

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
import com.example.domain.requests.topup.statsbycard.MonthTopupStatusCardNumber;
import com.example.domain.requests.topup.statsbycard.YearTopupStatusCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusSuccess;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusSuccess;
import com.example.repository.topup.statsbycard.TopupStatusByCardRepository;
import com.example.service.topup.stats.status.TopupStatusByCardService;

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
public class TopupStatusByCardServiceImpl implements TopupStatusByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TopupStatusByCardServiceImpl.class);

        private final TopupStatusByCardRepository topupStatusByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TopupStatusByCardServiceImpl(TopupStatusByCardRepository topupStatusByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.topupStatusByCardRepository = topupStatusByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("topup-status-by-card-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("topup-status-by-card-service");

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

        private void validateCard(String cardNumber) {
                if (cardNumber == null || cardNumber.isEmpty()) {
                        logger.error("❌ Card number is required");
                        throw new IllegalArgumentException("Card number is required");
                }
        }

        private void validateYearMonth(Long year, Integer month) {
                if (year == null || year < 1 || year > 9999) {
                        throw new IllegalArgumentException("Invalid year");
                }
                if (month == null || month < 1 || month > 12) {
                        throw new IllegalArgumentException("Invalid month");
                }
        }

        private void validateYear(Long year) {
                if (year == null || year < 1 || year > 9999) {
                        logger.error("❌ Invalid year: {}", year);
                        throw new IllegalArgumentException("Invalid year");
                }
        }

        @Override
        public Uni<ApiResponse<List<TopupResponseMonthStatusSuccess>>> findMonthStatusSuccess(
                        MonthTopupStatusCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                int month = req.getMonth();
                logger.info("📊 Fetching monthly SUCCESS topup status for card={} year={} month={}", cardNumber, year,
                                month);

                try {
                        validateCard(cardNumber);
                        validateYearMonth(year, month);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("topups:stats:card:status:month:success:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TopupResponseMonthStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TopupResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Successfully fetched monthly SUCCESS topup status for card="
                                                                                + cardNumber,
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-status-by-card-service")
                                                        .setAttribute("operation", "find_month_status_success")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return topupStatusByCardRepository
                                                        .findMonthTopupStatusSuccessByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(data -> {
                                                                List<TopupResponseMonthStatusSuccess> responseList = data
                                                                                .stream()
                                                                                .map(TopupResponseMonthStatusSuccess::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(responseList),
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
                                                                                                        "Successfully fetched monthly SUCCESS topup status for card="
                                                                                                                        + cardNumber,
                                                                                                        responseList);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly SUCCESS topup status for card={} month={} year={}",
                                                                                cardNumber, month, year, e);
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
                                                                                "Failed to fetch monthly SUCCESS topup status for card="
                                                                                                + cardNumber,
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
        public Uni<ApiResponse<List<TopupResponseYearStatusSuccess>>> findYearlyStatusSuccess(
                        YearTopupStatusCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                logger.info("📊 Fetching yearly SUCCESS topup status for card={} year={}", cardNumber, year);

                try {
                        validateCard(cardNumber);
                        validateYear(year);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("topups:stats:card:status:year:success:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TopupResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TopupResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Successfully fetched yearly SUCCESS topup status for card="
                                                                                + cardNumber,
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-status-by-card-service")
                                                        .setAttribute("operation", "find_yearly_status_success")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return topupStatusByCardRepository
                                                        .findYearlyTopupStatusSuccessByCard(cardNumber, year)
                                                        .chain(data -> {
                                                                List<TopupResponseYearStatusSuccess> responseList = data
                                                                                .stream()
                                                                                .map(TopupResponseYearStatusSuccess::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(responseList),
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
                                                                                                        "Successfully fetched yearly SUCCESS topup status for card="
                                                                                                                        + cardNumber,
                                                                                                        responseList);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly SUCCESS topup status for card={} year={}",
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
                                                                                "Failed to fetch yearly SUCCESS topup status for card="
                                                                                                + cardNumber,
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
        public Uni<ApiResponse<List<TopupResponseMonthStatusFailed>>> findMonthStatusFailed(
                        MonthTopupStatusCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                int month = req.getMonth();
                logger.info("📊 Fetching monthly FAILED topup status for card={} year={} month={}", cardNumber, year,
                                month);

                try {
                        validateCard(cardNumber);
                        validateYearMonth(year, month);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("topups:stats:card:status:month:failed:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TopupResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TopupResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Successfully fetched monthly FAILED topup status for card="
                                                                                + cardNumber,
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-status-by-card-service")
                                                        .setAttribute("operation", "find_month_status_failed")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return topupStatusByCardRepository
                                                        .findMonthTopupStatusFailedByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(data -> {
                                                                List<TopupResponseMonthStatusFailed> responseList = data
                                                                                .stream()
                                                                                .map(TopupResponseMonthStatusFailed::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(responseList),
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
                                                                                                        "Successfully fetched monthly FAILED topup status for card="
                                                                                                                        + cardNumber,
                                                                                                        responseList);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "💥 Failed to fetch monthly FAILED topup status for card={} month={} year={}",
                                                                                cardNumber, month, year, e);
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
                                                                                "Failed to fetch monthly FAILED topup status for card="
                                                                                                + cardNumber,
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
        public Uni<ApiResponse<List<TopupResponseYearStatusFailed>>> findYearlyStatusFailed(
                        YearTopupStatusCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = req.getYear();
                logger.info("📊 Fetching yearly FAILED topup status for card={} year={}", cardNumber, year);

                try {
                        validateCard(cardNumber);
                        validateYear(year);
                } catch (IllegalArgumentException e) {
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", e.getMessage(), Collections.emptyList()));
                }

                String cacheKey = String.format("topups:stats:card:status:year:failed:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TopupResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TopupResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Successfully fetched yearly FAILED topup status for card="
                                                                                + cardNumber,
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-status-by-card-service")
                                                        .setAttribute("operation", "find_yearly_status_failed")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return topupStatusByCardRepository
                                                        .findYearlyTopupStatusFailedByCard(cardNumber, year)
                                                        .chain(data -> {
                                                                List<TopupResponseYearStatusFailed> responseList = data
                                                                                .stream()
                                                                                .map(TopupResponseYearStatusFailed::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(responseList),
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

                                                                                        return ApiResponse
                                                                                                        .success("Successfully fetched yearly FAILED topup status for card="
                                                                                                                        + cardNumber,
                                                                                                                        responseList);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch yearly FAILED topup status for card={} year={}",
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
                                                                                "Failed to fetch yearly FAILED topup status for card="
                                                                                                + cardNumber,
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
