package com.example.service.impl.transfer.statsbycard;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.transfers.statsbycard.MonthYearCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.amount.TransferMonthAmountResponse;
import com.example.domain.responses.transfer.stats.amount.TransferYearAmountResponse;
import com.example.repository.transfer.statsbycard.TransferAmountByCardRepository;
import com.example.service.transfer.stats.amount.TransferAmountByCardService;

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
public class TransferAmountByCardImplService implements TransferAmountByCardService {
        private static final Logger logger = LoggerFactory.getLogger(TransferAmountByCardImplService.class);

        private final TransferAmountByCardRepository transferAmountByCardRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long STATS_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransferAmountByCardImplService(TransferAmountByCardRepository transferAmountByCardRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transferAmountByCardRepository = transferAmountByCardRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transfer-amount-bycard-stats-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transfer-amount-bycard-stats-service");

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

        private boolean isValidYearAndCard(Long year, String cardNumber) {
                return year != null && year >= 1 && year <= 9999 && cardNumber != null && !cardNumber.isBlank();
        }

        @Override
        public Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmountsBySender(MonthYearCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching monthly transfer amounts (SENDER) for year={}, cardNumber={}", year,
                                cardNumber);

                if (!isValidYearAndCard(year, cardNumber)) {
                        logger.error("❌ Invalid year or cardNumber provided: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("failed", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:amount:month:sender:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferMonthAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferMonthAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly transfer amounts (sender) retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthlyAmountsBySender")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-amount-bycard-stats-service")
                                                        .setAttribute("operation", "find_monthly_amounts_by_sender")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountByCardRepository
                                                        .findMonthlyTransferAmountsBySender(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransferMonthAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferMonthAmountResponse::from)
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
                                                                                                                        "find_monthly_amounts_by_sender",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly transfer amounts (sender) retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch monthly transfer amounts (sender) for year={}, cardNumber={}",
                                                                                year, cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts_by_sender",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("failed",
                                                                                "Failed to fetch monthly transfer amounts (sender)",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts_by_sender"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmountsByReceiver(
                        MonthYearCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching monthly transfer amounts (RECEIVER) for year={}, cardNumber={}", year,
                                cardNumber);

                if (!isValidYearAndCard(year, cardNumber)) {
                        logger.error("❌ Invalid year or cardNumber provided: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("failed", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:amount:month:receiver:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferMonthAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferMonthAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Monthly transfer amounts (receiver) retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMonthlyAmountsByReceiver")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-amount-bycard-stats-service")
                                                        .setAttribute("operation", "find_monthly_amounts_by_receiver")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountByCardRepository
                                                        .findMonthlyTransferAmountsByReceiver(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransferMonthAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferMonthAmountResponse::from)
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
                                                                                                                        "find_monthly_amounts_by_receiver",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Monthly transfer amounts (receiver) retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch monthly transfer amounts (receiver) for year={}, cardNumber={}",
                                                                                year, cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts_by_receiver",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("failed",
                                                                                "Failed to fetch monthly transfer amounts (receiver)",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_monthly_amounts_by_receiver"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmountsBySender(MonthYearCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching yearly transfer amounts (SENDER) until year={}, cardNumber={}", year,
                                cardNumber);

                if (!isValidYearAndCard(year, cardNumber)) {
                        logger.error("❌ Invalid year or cardNumber provided: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("failed", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:amount:year:sender:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferYearAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferYearAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly transfer amounts (sender) retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyAmountsBySender")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-amount-bycard-stats-service")
                                                        .setAttribute("operation", "find_yearly_amounts_by_sender")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountByCardRepository
                                                        .findYearlyTransferAmountsBySender(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransferYearAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferYearAmountResponse::from)
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
                                                                                                                        "find_yearly_amounts_by_sender",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly transfer amounts (sender) retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch yearly transfer amounts (sender) until year={}, cardNumber={}",
                                                                                year, cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts_by_sender",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("failed",
                                                                                "Failed to fetch yearly transfer amounts (sender)",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts_by_sender"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmountsByReceiver(MonthYearCardNumber req) {
                String cardNumber = req.getCardNumber();
                Long year = (long) req.getYear();

                logger.info("📊 Fetching yearly transfer amounts (RECEIVER) until year={}, cardNumber={}", year,
                                cardNumber);

                if (!isValidYearAndCard(year, cardNumber)) {
                        logger.error("❌ Invalid year or cardNumber provided: year={}, cardNumber={}", year, cardNumber);
                        return Uni.createFrom()
                                        .item(new ApiResponse<>("failed", "Invalid input parameters",
                                                        Collections.emptyList()));
                }

                String cacheKey = String.format("transfers:statsbycard:amount:year:receiver:%s:%d", cardNumber, year);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferYearAmountResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferYearAmountResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Yearly transfer amounts (receiver) retrieved successfully!",
                                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findYearlyAmountsByReceiver")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name",
                                                                        "transfer-amount-bycard-stats-service")
                                                        .setAttribute("operation", "find_yearly_amounts_by_receiver")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("year", String.valueOf(year))
                                                        .startSpan();

                                        return transferAmountByCardRepository
                                                        .findYearlyTransferAmountsByReceiver(cardNumber, year)
                                                        .chain(amounts -> {
                                                                List<TransferYearAmountResponse> response = amounts
                                                                                .stream()
                                                                                .map(TransferYearAmountResponse::from)
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
                                                                                                                        "find_yearly_amounts_by_receiver",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Yearly transfer amounts (receiver) retrieved successfully!",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error(
                                                                                "❌ Failed to fetch yearly transfer amounts (receiver) until year={}, cardNumber={}",
                                                                                year, cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts_by_receiver",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("failed",
                                                                                "Failed to fetch yearly transfer amounts (receiver)",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_yearly_amounts_by_receiver"));
                                                        });
                                });
        }
}
