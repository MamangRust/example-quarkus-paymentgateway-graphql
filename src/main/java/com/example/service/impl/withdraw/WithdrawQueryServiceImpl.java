package com.example.service.impl.withdraw;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.withdraws.FindAllWithdrawCardNumber;
import com.example.domain.requests.withdraws.FindAllWithdraws;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.withdraw.WithdrawResponse;
import com.example.domain.responses.withdraw.WithdrawResponseDeleteAt;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.withdraw.WithdrawQueryRepository;
import com.example.service.withdraw.WithdrawQueryService;

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
public class WithdrawQueryServiceImpl implements WithdrawQueryService {
        private static final Logger logger = LoggerFactory.getLogger(WithdrawQueryServiceImpl.class);

        private final WithdrawQueryRepository withdrawQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long QUERY_CACHE_TTL_SECONDS = 300;

        @Inject
        public WithdrawQueryServiceImpl(WithdrawQueryRepository withdrawQueryRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.withdrawQueryRepository = withdrawQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("withdraw-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("withdraw-query-service");

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

        @Override
        public Uni<ApiResponsePagination<List<WithdrawResponse>>> findAll(FindAllWithdraws req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🔍 Searching all withdraws | Page: {}, Size: {}, Search: {}", page + 1, pageSize,
                                search.isEmpty() ? "None" : search);

                String cacheKey = String.format("withdraws:all:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<WithdrawResponse>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<WithdrawResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllWithdraws")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_all")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return withdrawQueryRepository.findAllWithdraws(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<WithdrawResponse> data = pagedResult.getData()
                                                                                .stream()
                                                                                .map(WithdrawResponse::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<WithdrawResponse>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Withdraws retrieved successfully",
                                                                                data,
                                                                                meta);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch all withdraws", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>(
                                                                                "error",
                                                                                "Failed to fetch withdraws",
                                                                                Collections.emptyList(),
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<WithdrawResponse>>> findAllByCardNumber(FindAllWithdrawCardNumber req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";
                String cardNumber = req.getCardNumber();

                logger.info("💳 Searching withdraws by card number={} | Page: {}, Size: {} | Search {}",
                                cardNumber, page + 1, pageSize, search);

                String cacheKey = String.format("withdraws:card:%s:%d:%d:%s", cardNumber, page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<WithdrawResponse>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<WithdrawResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllWithdrawsByCardNumber")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_all_by_card_number")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return withdrawQueryRepository
                                                        .findAllByCardNumber(cardNumber, search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<WithdrawResponse> data = pagedResult.getData()
                                                                                .stream()
                                                                                .map(WithdrawResponse::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<WithdrawResponse>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Withdraws by card number retrieved successfully",
                                                                                data,
                                                                                meta);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_by_card_number",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch withdraws by card number={}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_by_card_number",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>(
                                                                                "error",
                                                                                "Failed to fetch withdraws by card number",
                                                                                Collections.emptyList(),
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_by_card_number"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<WithdrawResponse>> findById(Long withdrawId) {
                logger.info("🔍 Finding withdraw by id={}", withdrawId);

                String cacheKey = String.format("withdraws:id:%d", withdrawId);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                WithdrawResponse cached = fromJson(cachedJson,
                                                                new TypeReference<WithdrawResponse>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Withdraw retrieved successfully", cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findWithdrawById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_by_id")
                                                        .setAttribute("withdrawId", String.valueOf(withdrawId))
                                                        .startSpan();

                                        return withdrawQueryRepository.findById(withdrawId)
                                                        .chain(w -> {
                                                                if (w == null) {
                                                                        throw new ResourceNotFoundException(
                                                                                        "Withdraw not found with id "
                                                                                                        + withdrawId);
                                                                }

                                                                WithdrawResponse response = WithdrawResponse.from(w);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Withdraw retrieved successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch withdraw id={}",
                                                                                withdrawId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_id",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error", "Withdraw not found",
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_id"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<WithdrawResponse>>> findByCard(String cardNumber) {
                logger.info("💳 Finding withdraws list by card number={}", cardNumber);

                String cacheKey = String.format("withdraws:list:card:%s", cardNumber);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<WithdrawResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<WithdrawResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Withdraws by card number retrieved successfully",
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findWithdrawsByCardNumberList")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .startSpan();

                                        return withdrawQueryRepository.findByCardNumber(cardNumber)
                                                        .chain(withdraws -> {
                                                                List<WithdrawResponse> data = withdraws.stream()
                                                                                .map(WithdrawResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService.setWithExpirationReactive(cacheKey,
                                                                                toJson(data), QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Withdraws by card number retrieved successfully",
                                                                                                        data);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch withdraws by card number={}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch withdraws by card number",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findByActive(FindAllWithdraws req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("📂 Searching active withdraws | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("withdraws:active:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<WithdrawResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<WithdrawResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveWithdraws")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_active")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return withdrawQueryRepository.findActiveWithdraws(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<WithdrawResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(WithdrawResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<WithdrawResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Active withdraws retrieved successfully",
                                                                                data,
                                                                                meta);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch active withdraws", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>(
                                                                                "error",
                                                                                "Failed to fetch active withdraws",
                                                                                Collections.emptyList(),
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findByTrashed(FindAllWithdraws req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🗑️ Searching trashed withdraws | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("withdraws:trashed:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<WithdrawResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<WithdrawResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedWithdraws")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "withdraw-query-service")
                                                        .setAttribute("operation", "find_trashed")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return withdrawQueryRepository.findTrashedWithdraws(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<WithdrawResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(WithdrawResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<WithdrawResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Trashed withdraws retrieved successfully",
                                                                                data,
                                                                                meta);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch trashed withdraws", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>(
                                                                                "error",
                                                                                "Failed to fetch trashed withdraws",
                                                                                Collections.emptyList(),
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed"));
                                                        });
                                });
        }
}
