package com.example.service.impl.merchant;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.merchant.FindAllMerchants;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsByApiKey;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsById;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.merchant.MerchantTransactionResponse;
import com.example.repository.merchant.MerchantTransactionRepository;
import com.example.service.merchant.MerchantTransactionService;

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
public class MerchantTransactionServiceImpl implements MerchantTransactionService {
        private static final Logger logger = LoggerFactory.getLogger(MerchantTransactionServiceImpl.class);

        private final MerchantTransactionRepository merchantTransactionRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public MerchantTransactionServiceImpl(MerchantTransactionRepository merchantTransactionRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.merchantTransactionRepository = merchantTransactionRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("merchant-transaction-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("merchant-transaction-service");

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
                        logger.error("Error deserializing JSON to object with TypeReference", e);
                        throw new RuntimeException("Failed to deserialize JSON", e);
                }
        }

        @Override
        public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findAll(FindAllMerchants req) {
                String cacheKey = String.format("merchant-tx:all:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantTransactionResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllMerchantTransactions")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-transaction-service")
                                                        .setAttribute("operation", "find_all_merchant_transactions")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantTransactionRepository.findAllTransactions(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("tx.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("tx.page", req.getPage());
                                                                span.setAttribute("tx.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Merchant transactions retrieved successfully",
                                                                                MerchantTransactionResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} merchant transactions",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_merchant_transactions",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all merchant transactions",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_merchant_transactions",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_merchant_transactions"));
                                                                logger.debug("Find all merchant transactions operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findById(
                        FindAllMerchantTransactionsById req) {
                String cacheKey = String.format("merchant-tx:id:%d:%d:%d:%s", req.getMerchantId(), req.getPage(),
                                req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantTransactionResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMerchantTransactionsById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-transaction-service")
                                                        .setAttribute("operation", "find_merchant_transactions_by_id")
                                                        .setAttribute("merchant.id", req.getMerchantId().toString())
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantTransactionRepository
                                                        .findAllTransactionsByMerchant(req.getMerchantId(), search,
                                                                        page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("tx.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("tx.page", req.getPage());
                                                                span.setAttribute("tx.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Merchant transactions retrieved successfully",
                                                                                MerchantTransactionResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} merchant transactions for merchant id {}",
                                                                                                        pagedResult.getTotalRecords(),
                                                                                                        req.getMerchantId());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_merchant_transactions_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding merchant transactions by merchant id",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_transactions_by_id",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_transactions_by_id"));
                                                                logger.debug("Find merchant transactions by merchant id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findByApiKey(
                        FindAllMerchantTransactionsByApiKey req) {
                String cacheKey = String.format("merchant-tx:apikey:%s:%d:%d:%s", req.getApiKey(), req.getPage(),
                                req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantTransactionResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMerchantTransactionsByApiKey")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-transaction-service")
                                                        .setAttribute("operation",
                                                                        "find_merchant_transactions_by_api_key")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantTransactionRepository
                                                        .findAllTransactionsByApiKey(req.getApiKey(), search, page,
                                                                        size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("tx.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("tx.page", req.getPage());
                                                                span.setAttribute("tx.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantTransactionResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Merchant transactions retrieved successfully",
                                                                                MerchantTransactionResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} merchant transactions for api key",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_merchant_transactions_by_api_key",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding merchant transactions by api key",
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_transactions_by_api_key",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_transactions_by_api_key"));
                                                                logger.debug("Find merchant transactions by api key operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        int pageReq,
                        int pageSizeReq,
                        String successMessage,
                        Function<T, R> mapper) {

                List<R> data = pagedResult.getData().stream()
                                .map(mapper)
                                .collect(Collectors.toList());

                int totalRecords = pagedResult.getTotalRecords();
                int size = pageSizeReq > 0 ? pageSizeReq : 1;
                int totalPages = (int) Math.ceil((double) totalRecords / size);

                PaginationMeta pagination = new PaginationMeta(pageReq, size, totalPages, totalRecords);

                return new ApiResponsePagination<>("success", successMessage, data, pagination);
        }
}
