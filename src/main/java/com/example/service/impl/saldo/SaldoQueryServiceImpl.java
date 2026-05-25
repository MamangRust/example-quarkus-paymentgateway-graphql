package com.example.service.impl.saldo;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.saldo.FindAllSaldos;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.saldo.SaldoResponse;
import com.example.domain.responses.saldo.SaldoResponseDeleteAt;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.saldo.SaldoQueryRepository;
import com.example.service.saldo.SaldoQueryService;

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
public class SaldoQueryServiceImpl implements SaldoQueryService {
        private static final Logger logger = LoggerFactory.getLogger(SaldoQueryServiceImpl.class);

        private final SaldoQueryRepository saldoQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public SaldoQueryServiceImpl(SaldoQueryRepository saldoQueryRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.saldoQueryRepository = saldoQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("saldo-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("saldo-query-service");

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

        private <T> T fromJson(String json, Class<T> clazz) {
                try {
                        return objectMapper.readValue(json, clazz);
                } catch (JsonProcessingException e) {
                        logger.error("Error deserializing JSON to class", e);
                        throw new RuntimeException("Failed to deserialize JSON", e);
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
        public Uni<ApiResponsePagination<List<SaldoResponse>>> findAll(FindAllSaldos req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String keyword = (req.getSearch() != null && !req.getSearch().isEmpty()) ? req.getSearch() : "";

                String cacheKey = String.format("saldos:all:%d:%d:%s", page, pageSize, keyword);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<SaldoResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<SaldoResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllSaldos")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "saldo-query-service")
                                                        .setAttribute("operation", "find_all_saldos")
                                                        .startSpan();

                                        return saldoQueryRepository.findSaldos(keyword, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("saldo.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("saldo.page", req.getPage());
                                                                span.setAttribute("saldo.size", req.getPageSize());

                                                                ApiResponsePagination<List<SaldoResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Get all saldos success",
                                                                                SaldoResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} saldos",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_saldos",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all saldos", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_saldos",
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
                                                                                "find_all_saldos"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findActive(FindAllSaldos req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String keyword = (req.getSearch() != null && !req.getSearch().isEmpty()) ? req.getSearch() : "";

                String cacheKey = String.format("saldos:active:%d:%d:%s", page, pageSize, keyword);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<SaldoResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<SaldoResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveSaldos")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "saldo-query-service")
                                                        .setAttribute("operation", "find_active_saldos")
                                                        .startSpan();

                                        return saldoQueryRepository.findActiveSaldos(keyword, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("saldo.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("saldo.page", req.getPage());
                                                                span.setAttribute("saldo.size", req.getPageSize());

                                                                ApiResponsePagination<List<SaldoResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Get active saldos success",
                                                                                SaldoResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} active saldos",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_saldos",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding active saldos", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_saldos",
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
                                                                                "find_active_saldos"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findTrashed(FindAllSaldos req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String keyword = (req.getSearch() != null && !req.getSearch().isEmpty()) ? req.getSearch() : "";

                String cacheKey = String.format("saldos:trashed:%d:%d:%s", page, pageSize, keyword);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<SaldoResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<SaldoResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedSaldos")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "saldo-query-service")
                                                        .setAttribute("operation", "find_trashed_saldos")
                                                        .startSpan();

                                        return saldoQueryRepository.findTrashedSaldos(keyword, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("saldo.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("saldo.page", req.getPage());
                                                                span.setAttribute("saldo.size", req.getPageSize());

                                                                ApiResponsePagination<List<SaldoResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Get trashed saldos success",
                                                                                SaldoResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} trashed saldos",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_saldos",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding trashed saldos", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_saldos",
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
                                                                                "find_trashed_saldos"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<SaldoResponse>> findByCard(String cardNumber) {
                String cacheKey = "saldo:card:" + cardNumber;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                SaldoResponse cachedSaldo = fromJson(cachedJson, SaldoResponse.class);
                                                return Uni.createFrom().item(
                                                                ApiResponse.success("Get saldo success", cachedSaldo));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findSaldoByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "saldo-query-service")
                                                        .setAttribute("operation", "find_saldo_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .startSpan();

                                        return saldoQueryRepository.findByCardNumber(cardNumber)
                                                        .chain(saldo -> {
                                                                if (saldo == null) {
                                                                        logger.warn("Saldo not found for card: {}",
                                                                                        cardNumber);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Saldo not found");
                                                                        span.setAttribute("saldo.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_saldo_by_card",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new ResourceNotFoundException(
                                                                                        "Saldo not found");
                                                                }

                                                                span.setAttribute("saldo.found", true);
                                                                span.setAttribute("saldo.id",
                                                                                saldo.getSaldoId().toString());

                                                                SaldoResponse response = SaldoResponse.from(saldo);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found saldo for card: {}",
                                                                                                        cardNumber);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_saldo_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return ApiResponse.success(
                                                                                                        "Get saldo success",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding saldo by card number: {}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_saldo_by_card",
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
                                                                                "find_saldo_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<SaldoResponse>> findById(Long id) {
                String cacheKey = "saldo:id:" + id;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                SaldoResponse cachedSaldo = fromJson(cachedJson, SaldoResponse.class);
                                                return Uni.createFrom().item(
                                                                ApiResponse.success("Get saldo success", cachedSaldo));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findSaldoById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "saldo-query-service")
                                                        .setAttribute("operation", "find_saldo_by_id")
                                                        .setAttribute("saldo.id", id.toString())
                                                        .startSpan();

                                        return saldoQueryRepository.findById(id)
                                                        .chain(saldo -> {
                                                                if (saldo == null) {
                                                                        logger.warn("Saldo not found for id: {}", id);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Saldo not found");
                                                                        span.setAttribute("saldo.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_saldo_by_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new ResourceNotFoundException(
                                                                                        "Saldo not found");
                                                                }

                                                                span.setAttribute("saldo.found", true);

                                                                SaldoResponse response = SaldoResponse.from(saldo);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found saldo for id: {}",
                                                                                                        id);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_saldo_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return ApiResponse.success(
                                                                                                        "Get saldo success",
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding saldo by id: {}", id, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_saldo_by_id",
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
                                                                                "find_saldo_by_id"));
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        FindAllSaldos request,
                        String successMessage,
                        Function<T, R> mapper) {

                List<R> data = pagedResult.getData().stream()
                                .map(mapper)
                                .collect(Collectors.toList());

                int totalRecords = pagedResult.getTotalRecords();
                int size = request.getPageSize() > 0 ? request.getPageSize() : 1;
                int totalPages = (int) Math.ceil((double) totalRecords / size);

                PaginationMeta pagination = new PaginationMeta(request.getPage(), size, totalPages, totalRecords);

                return new ApiResponsePagination<>("success", successMessage, data, pagination);
        }
}
