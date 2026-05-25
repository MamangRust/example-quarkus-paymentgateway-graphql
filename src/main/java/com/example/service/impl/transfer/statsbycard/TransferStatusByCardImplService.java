package com.example.service.impl.transfer.statsbycard;

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
import com.example.domain.requests.transfers.statsbycard.MonthStatusTransferCardNumber;
import com.example.domain.requests.transfers.statsbycard.YearStatusTransferCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusSuccess;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusSuccess;
import com.example.repository.transfer.statsbycard.TransferStatusByCardRepository;
import com.example.service.transfer.stats.status.TransferStatusByCardService;

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
public class TransferStatusByCardImplService implements TransferStatusByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TransferStatusByCardImplService.class);

        private final TransferStatusByCardRepository transferStatusByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransferStatusByCardImplService(TransferStatusByCardRepository transferStatusByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transferStatusByCardRepository = transferStatusByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transfer-status-bycard-stats-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transfer-status-bycard-stats-service");

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

        private boolean isValidYearMonth(Long year, Integer month) {
                return isValidYear(year) && month != null && month >= 1 && month <= 12;
        }

        @Override
        public Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> findMonthStatusSuccessByCard(
                        MonthStatusTransferCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly SUCCESS transfer status for card: {}, year: {}, month: {}", cardNumber,
                                year,
                                month);

                if (!isValidYearMonth(year, month) || cardNumber == null || cardNumber.isBlank()) {
                        logger.error("❌ Invalid input parameters: year={}, month={}, cardNumber={}", year, month,
                                        cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:status:month:success:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseMonthStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly SUCCESS transfer status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-status-bycard-stats-service")
                                                        .setAttribute("operation", "find_month_status_success_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transferStatusByCardRepository
                                                        .findMonthTransferStatusSuccessByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<TransferResponseMonthStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(TransferResponseMonthStatusSuccess::from)
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
                                                                                                                        "find_month_status_success_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly SUCCESS transfer status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch monthly SUCCESS transfer status for card={}, year={}, month={}",
                                                                                cardNumber, year, month, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_success_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Monthly SUCCESS transfer status by card fetching failed",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_success_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> findYearlyStatusSuccessByCard(
                        YearStatusTransferCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching yearly SUCCESS transfer status for card: {}, year: {}", cardNumber, year);

                if (!isValidYear(year) || cardNumber == null || cardNumber.isBlank()) {
                        logger.error("❌ Invalid input parameters: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:status:year:success:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly SUCCESS transfer status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusSuccessByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-status-bycard-stats-service")
                                                        .setAttribute("operation", "find_yearly_status_success_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferStatusByCardRepository
                                                        .findYearlyTransferStatusSuccessByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<TransferResponseYearStatusSuccess> response = results
                                                                                .stream()
                                                                                .map(TransferResponseYearStatusSuccess::from)
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
                                                                                                                        "find_yearly_status_success_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly SUCCESS transfer status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly SUCCESS transfer status for card={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_success_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Yearly SUCCESS transfer status by card fetching failed",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_success_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> findMonthStatusFailedByCard(
                        MonthStatusTransferCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly FAILED transfer status for card: {}, year: {}, month: {}", cardNumber,
                                year,
                                month);

                if (!isValidYearMonth(year, month) || cardNumber == null || cardNumber.isBlank()) {
                        logger.error("❌ Invalid input parameters: year={}, month={}, cardNumber={}", year, month,
                                        cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:status:month:failed:%s:%d:%d", cardNumber, year,
                                month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly FAILED transfer status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-status-bycard-stats-service")
                                                        .setAttribute("operation", "find_month_status_failed_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transferStatusByCardRepository
                                                        .findMonthTransferStatusFailedByCard(cardNumber, year, month,
                                                                        (long) nextMonth.getYear(),
                                                                        nextMonth.getMonthValue())
                                                        .chain(results -> {
                                                                List<TransferResponseMonthStatusFailed> response = results
                                                                                .stream()
                                                                                .map(TransferResponseMonthStatusFailed::from)
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
                                                                                                                        "find_month_status_failed_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly FAILED transfer status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch monthly FAILED transfer status for card={}, year={}, month={}",
                                                                                cardNumber, year, month, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_failed_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Monthly FAILED transfer status by card fetching failed",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_month_status_failed_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> findYearlyStatusFailedByCard(
                        YearStatusTransferCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching yearly FAILED transfer status for card: {}, year: {}", cardNumber, year);

                if (!isValidYear(year) || cardNumber == null || cardNumber.isBlank()) {
                        logger.error("❌ Invalid input parameters: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:status:year:failed:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly FAILED transfer status by card fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyStatusFailedByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-status-bycard-stats-service")
                                                        .setAttribute("operation", "find_yearly_status_failed_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferStatusByCardRepository
                                                        .findYearlyTransferStatusFailedByCard(cardNumber, year)
                                                        .chain(results -> {
                                                                List<TransferResponseYearStatusFailed> response = results
                                                                                .stream()
                                                                                .map(TransferResponseYearStatusFailed::from)
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
                                                                                                                        "find_yearly_status_failed_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly FAILED transfer status by card fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly FAILED transfer status for card={}, year={}",
                                                                                cardNumber, year, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_failed_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Yearly FAILED transfer status by card fetching failed",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_status_failed_by_card"));
                                                        });
                                });
        }
}
