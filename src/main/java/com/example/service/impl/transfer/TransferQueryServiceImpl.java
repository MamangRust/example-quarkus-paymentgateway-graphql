package com.example.service.impl.transfer;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.transfers.FindAllTransfers;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.transfer.TransferResponse;
import com.example.domain.responses.transfer.TransferResponseDeleteAt;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.transfer.TransferQueryRepository;
import com.example.service.transfer.TransferQueryService;

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
public class TransferQueryServiceImpl implements TransferQueryService {
        private static final Logger logger = LoggerFactory.getLogger(TransferQueryServiceImpl.class);

        private final TransferQueryRepository transferQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long QUERY_CACHE_TTL_SECONDS = 300;

        @Inject
        public TransferQueryServiceImpl(TransferQueryRepository transferQueryRepository,
                        RedisService redisService,
                        ObjectMapper objectMapper,
                        OpenTelemetry openTelemetry) {
                this.transferQueryRepository = transferQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("transfer-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("transfer-query-service");

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
        public Uni<ApiResponsePagination<List<TransferResponse>>> findAll(FindAllTransfers req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🔍 Searching all transfers | Page: {}, Size: {}, Search: {}", page + 1, pageSize,
                                search.isEmpty() ? "None" : search);

                String cacheKey = String.format("transfers:all:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransferResponse>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransferResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllTransfers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_all")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transferQueryRepository.findTransfers(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransferResponse> data = pagedResult.getData()
                                                                                .stream()
                                                                                .map(TransferResponse::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransferResponse>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Transfers retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch transfers", e);
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
                                                                                "Failed to fetch transfers",
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
        public Uni<ApiResponse<TransferResponse>> findById(Long transferId) {
                logger.info("🔍 Finding transfer by id={}", transferId);

                String cacheKey = String.format("transfers:id:%d", transferId);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                TransferResponse cached = fromJson(cachedJson,
                                                                new TypeReference<TransferResponse>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Transfer retrieved successfully", cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTransferById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_by_id")
                                                        .setAttribute("transferId", String.valueOf(transferId))
                                                        .startSpan();

                                        return transferQueryRepository.findTransferById(transferId)
                                                        .chain(t -> {
                                                                if (t == null) {
                                                                        throw new ResourceNotFoundException(
                                                                                        "Transfer not found with id "
                                                                                                        + transferId);
                                                                }

                                                                TransferResponse response = TransferResponse.from(t);

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
                                                                                                        "Transfer retrieved successfully",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch transfer by id={}",
                                                                                transferId, e);
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
        public Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findByActive(FindAllTransfers req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("📂 Searching active transfers | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("transfers:active:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransferResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransferResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveTransfers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_active")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transferQueryRepository.findActiveTransfers(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransferResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(TransferResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransferResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Active transfers retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch active transfers", e);
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
                                                                                "Failed to fetch active transfers",
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
        public Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findByTrashed(FindAllTransfers req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                logger.info("🗑️ Searching trashed transfers | Page: {}, Size: {}", page + 1, pageSize);

                String cacheKey = String.format("transfers:trashed:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TransferResponseDeleteAt>> cached = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TransferResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(cached);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedTransfers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_trashed")
                                                        .setAttribute("page", String.valueOf(page))
                                                        .setAttribute("size", String.valueOf(pageSize))
                                                        .startSpan();

                                        return transferQueryRepository.findTrashedTransfers(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                List<TransferResponseDeleteAt> data = pagedResult
                                                                                .getData().stream()
                                                                                .map(TransferResponseDeleteAt::from)
                                                                                .collect(Collectors.toList());

                                                                PaginationMeta meta = new PaginationMeta(
                                                                                pagedResult.getTotalRecords(),
                                                                                page + 1,
                                                                                pageSize,
                                                                                (int) Math.ceil((double) pagedResult
                                                                                                .getTotalRecords()
                                                                                                / pageSize));

                                                                ApiResponsePagination<List<TransferResponseDeleteAt>> response = new ApiResponsePagination<>(
                                                                                "success",
                                                                                "Trashed transfers retrieved successfully",
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
                                                                logger.error("💥 Failed to fetch trashed transfers", e);
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
                                                                                "Failed to fetch trashed transfers",
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
        public Uni<ApiResponse<List<TransferResponse>>> findByTransferFrom(String transferFrom) {
                logger.info("💸 Finding transfers by transferFrom={}", transferFrom);

                String cacheKey = String.format("transfers:from:%s", transferFrom);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Transfers by sender retrieved successfully", cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTransfersByTransferFrom")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_by_transfer_from")
                                                        .setAttribute("transferFrom", transferFrom)
                                                        .startSpan();

                                        return transferQueryRepository.findTransfersBySourceCard(transferFrom)
                                                        .chain(transfers -> {
                                                                List<TransferResponse> data = transfers.stream()
                                                                                .map(TransferResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService.setWithExpirationReactive(cacheKey,
                                                                                toJson(data), QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_by_transfer_from",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Transfers by sender retrieved successfully",
                                                                                                        data);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch transfers by transferFrom={}",
                                                                                transferFrom, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_transfer_from",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch transfers",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_transfer_from"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TransferResponse>>> findByTransferTo(String transferTo) {
                logger.info("💸 Finding transfers by transferTo={}", transferTo);

                String cacheKey = String.format("transfers:to:%s", transferTo);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TransferResponse> cached = fromJson(cachedJson,
                                                                new TypeReference<List<TransferResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Transfers by receiver retrieved successfully",
                                                                cached));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTransfersByTransferTo")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "transfer-query-service")
                                                        .setAttribute("operation", "find_by_transfer_to")
                                                        .setAttribute("transferTo", transferTo)
                                                        .startSpan();

                                        return transferQueryRepository.findTransfersByDestinationCard(transferTo)
                                                        .chain(transfers -> {
                                                                List<TransferResponse> data = transfers.stream()
                                                                                .map(TransferResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService.setWithExpirationReactive(cacheKey,
                                                                                toJson(data), QUERY_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_by_transfer_to",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Transfers by receiver retrieved successfully",
                                                                                                        data);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("💥 Failed to fetch transfers by transferTo={}",
                                                                                transferTo, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_transfer_to",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch transfers",
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_by_transfer_to"));
                                                        });
                                });
        }
}
