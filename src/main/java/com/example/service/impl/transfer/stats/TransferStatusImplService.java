package com.example.service.impl.transfer.stats;

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
import com.example.domain.requests.transfers.stats.MonthStatusTransfer;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusSuccess;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusSuccess;
import com.example.repository.transfer.stats.TransferStatusRepository;
import com.example.service.transfer.stats.status.TransferStatusService;

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
public class TransferStatusImplService implements TransferStatusService {
        private static final Logger logger = LoggerFactory.getLogger(TransferStatusImplService.class);

        private final TransferStatusRepository transferStatusRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransferStatusImplService(TransferStatusRepository transferStatusRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transferStatusRepository = transferStatusRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transfer-status-stats-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transfer-status-stats-service");

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
        public Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> findMonthStatusSuccess(
                        MonthStatusTransfer req) {
                Long year = (long) req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly SUCCESS transfer status for year: {}, month: {}", year, month);

                if (!isValidYearMonth(year, month)) {
                        logger.error("❌ Invalid year/month: year={}, month={}", year, month);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid year or month", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:status:month:success:%d:%d", year, month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseMonthStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseMonthStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Monthly SUCCESS transfer status fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthTransferStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-status-stats-service")
                                                        .setAttribute("operation", "find_month_status_success")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transferStatusRepository
                                                        .findMonthTransferStatusSuccess(year, month,
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
                                                                                                                        "find_month_status_success",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly SUCCESS transfer status fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch monthly SUCCESS transfer status for year={}, month={}",
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
                                                                                "Failed to retrieve monthly SUCCESS transfer status",
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
        public Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> findMonthStatusFailed(
                        MonthStatusTransfer req) {
                Long year = (long) req.getYear();
                int month = req.getMonth();

                logger.info("📊 Fetching monthly FAILED transfer status for year: {}, month: {}", year, month);

                if (!isValidYearMonth(year, month)) {
                        logger.error("❌ Invalid year/month: year={}, month={}", year, month);
                        return Uni.createFrom().item(
                                        new ApiResponse<>("error", "Invalid year or month", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:status:month:failed:%d:%d", year, month);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseMonthStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseMonthStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Monthly FAILED transfer status fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthTransferStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-status-stats-service")
                                                        .setAttribute("operation", "find_month_status_failed")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .setAttribute("month", String.valueOf(month))
                                                        .startSpan();

                                        LocalDate currentMonth = LocalDate.of(year.intValue(), month, 1);
                                        LocalDate nextMonth = currentMonth.plusMonths(1);

                                        return transferStatusRepository
                                                        .findMonthTransferStatusFailed(year, month,
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
                                                                                                                        "find_month_status_failed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly FAILED transfer status fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch monthly FAILED transfer status for year={}, month={}",
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
                                                                                "Failed to retrieve monthly FAILED transfer status",
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
        public Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year) {
                logger.info("📊 Fetching yearly SUCCESS transfer status for year: {}", year);

                if (!isValidYear(year)) {
                        logger.error("❌ Invalid year: {}", year);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid year", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:status:year:success:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseYearStatusSuccess> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseYearStatusSuccess>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Yearly SUCCESS transfer status fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyTransferStatusSuccess")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-status-stats-service")
                                                        .setAttribute("operation", "find_yearly_status_success")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferStatusRepository.findYearlyTransferStatusSuccess(year)
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
                                                                                                                        "find_yearly_status_success",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly SUCCESS transfer status fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly SUCCESS transfer status for year={}",
                                                                                year, e);
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
                                                                                "Failed to retrieve yearly SUCCESS transfer status",
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
        public Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> findYearlyStatusFailed(Long year) {
                logger.info("📊 Fetching yearly FAILED transfer status for year: {}", year);

                if (!isValidYear(year)) {
                        logger.error("❌ Invalid year: {}", year);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("error", "Invalid year", Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:stats:status:year:failed:%d", year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponseYearStatusFailed> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponseYearStatusFailed>>() {
                                                                });
                                                return Uni.createFrom().item(
                                                                ApiResponse.success(
                                                                                "Yearly FAILED transfer status fetched successfully",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyTransferStatusFailed")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-status-stats-service")
                                                        .setAttribute("operation", "find_yearly_status_failed")
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferStatusRepository.findYearlyTransferStatusFailed(year)
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
                                                                                                                        "find_yearly_status_failed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly FAILED transfer status fetched successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("❌ Failed to fetch yearly FAILED transfer status for year={}",
                                                                                year, e);
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
                                                                                "Failed to retrieve yearly FAILED transfer status",
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
