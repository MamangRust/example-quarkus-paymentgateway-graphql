package com.example.service.impl.transaction;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.transaction.FindAllTransactionCardNumber;
import com.example.domain.requests.transaction.FindAllTransactions;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.transaction.TransactionResponse;
import com.example.domain.responses.transaction.TransactionResponseDeleteAt;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.transaction.TransactionQueryRepository;
import com.example.service.transaction.TransactionQueryService;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TransactionQueryServiceImpl implements TransactionQueryService {
        private static final Logger logger = LoggerFactory.getLogger(TransactionQueryServiceImpl.class);

        private final TransactionQueryRepository transactionQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long QUERY_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransactionQueryServiceImpl(TransactionQueryRepository transactionQueryRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transactionQueryRepository = transactionQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transaction-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transaction-query-service");

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
        public Uni<ApiResponsePagination<List<TransactionResponse>>> findAll(FindAllTransactions req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🔍 Searching all transactions | Page: {}, Size: {}, Search: {}", page + 1, pageSize,
                                search.isEmpty() ? "None" : search);

                String cacheKey = String.format("transactions:all:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransactionResponse>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransactionResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllTransactions")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_all")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transactionQueryRepository.findTransactions(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransactionResponse> data = pagedResult.getData()
                                                                                .stream()
                                                                                .map(TransactionResponse::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransactionResponse>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Transactions retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch all transactions", e);
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
                                                                                "Failed to fetch transactions",
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
        public Uni<ApiResponsePagination<List<TransactionResponse>>> findAllByCardNumber(
                        FindAllTransactionCardNumber req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";
                String cardNumber = req.getCardNumber();

                logger.info("💳 Searching transactions by cardNumber={} | Page: {}, Size: {}",
                                cardNumber, page + 1, pageSize);

                if (cardNumber == null || cardNumber.trim().isEmpty()) {
                        return Uni.createFrom().item(new ApiResponsePagination<>(
                                        "error",
                                        "Card number wajib diisi",
                                        Collections.emptyList(),
                                        null));
                }

                String cacheKey = String.format("transactions:card-num:%s:%d:%d:%s", cardNumber, page, pageSize,
                                search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransactionResponse>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransactionResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllTransactionsByCardNumber")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_all_by_card_number")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transactionQueryRepository
                                                        .findTransactionsByCardNumber(cardNumber, search, page,
                                                                        pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransactionResponse> data = pagedResult.getData()
                                                                                .stream()
                                                                                .map(TransactionResponse::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransactionResponse>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Transactions retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch transactions by cardNumber={}",
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
                                                                                "Failed to fetch transactions",
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
        public Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findByActive(FindAllTransactions req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("📂 Searching active transactions | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("transactions:active:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransactionResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransactionResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveTransactions")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_active")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transactionQueryRepository.findActiveTransactions(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransactionResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(TransactionResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransactionResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Active transactions retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch active transactions",
                                                                                e);
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
                                                                                "Failed to fetch transactions",
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
        public Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findByTrashed(FindAllTransactions req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🗑️ Searching trashed transactions | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("transactions:trashed:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransactionResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransactionResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedTransactions")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_trashed")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transactionQueryRepository
                                                        .findTrashedTransactions(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransactionResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(TransactionResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransactionResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Trashed transactions retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch trashed transactions",
                                                                                e);
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
                                                                                "Failed to fetch transactions",
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

        @Override
        public Uni<ApiResponse<TransactionResponse>> findById(Long transactionId) {
                logger.info("🔍 Finding transaction by id={}", transactionId);

                String cacheKey = String.format("transactions:id:%d", transactionId);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                TransactionResponse cached = fromJson(cachedJson,
                                                                new TypeReference<TransactionResponse>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Transaction retrieved successfully", cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTransactionById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_by_id")
                                                        .setAttribute("transactionId", String.valueOf(transactionId))
                                                        .startSpan();

                                        return transactionQueryRepository.findTransactionById(transactionId)
                                                        .chain(tx -> {
                                                                if (tx == null) {
                                                                        throw new ResourceNotFoundException(
                                                                                        "Transaction not found");
                                                                }

                                                                TransactionResponse response = TransactionResponse
                                                                                .from(tx);

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
                                                                                                        "Transaction retrieved successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch transaction by id={}",
                                                                                transactionId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_id",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error", e.getMessage(), null);
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
        public Uni<ApiResponse<List<TransactionResponse>>> findByMerchantId(Long merchantId) {
                logger.info("🏪 Finding transactions by merchantId={}", merchantId);

                String cacheKey = String.format("transactions:merchant:%d", merchantId);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransactionResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransactionResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Transactions retrieved successfully by merchant id",
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTransactionsByMerchantId")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transaction-query-service")
                                                        .setAttribute("operation", "find_by_merchant_id")
                                                        .setAttribute("merchantId", String.valueOf(merchantId))
                                                        .startSpan();

                                        return transactionQueryRepository.findTransactionsByMerchantId(merchantId)
                                                        .chain(transactions -> {
                                                                List<TransactionResponse> data = transactions.stream()
                                                                                .map(TransactionResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService.setWithExpirationReactive(cacheKey,
                                                                                toJson(data), QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_by_merchant_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Transactions retrieved successfully by merchant id",
                                                                                                        data);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch transactions by merchantId={}",
                                                                                merchantId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_merchant_id",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch transactions by merchant id",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_merchant_id"));
                                                        });
                                });
        }
}
