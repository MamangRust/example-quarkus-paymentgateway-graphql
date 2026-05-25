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
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.merchant.MerchantResponse;
import com.example.domain.responses.merchant.MerchantResponseDeleteAt;
import com.example.repository.merchant.MerchantQueryRepository;
import com.example.service.merchant.MerchantQueryService;

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
import jakarta.ws.rs.NotFoundException;

@ApplicationScoped
public class MerchantQueryServiceImpl implements MerchantQueryService {
        private static final Logger logger = LoggerFactory.getLogger(MerchantQueryServiceImpl.class);

        private final MerchantQueryRepository merchantQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public MerchantQueryServiceImpl(MerchantQueryRepository merchantQueryRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.merchantQueryRepository = merchantQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("merchant-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("merchant-query-service");

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
                        logger.error("Error deserializing JSON to object", e);
                        throw new RuntimeException("Failed to deserialize JSON", e);
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
        public Uni<ApiResponsePagination<List<MerchantResponse>>> findAll(FindAllMerchants req) {
                String cacheKey = String.format("merchants:all:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllMerchants")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_all_merchants")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantQueryRepository.findMerchants(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("merchant.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("merchant.page", req.getPage());
                                                                span.setAttribute("merchant.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Merchants retrieved successfully",
                                                                                MerchantResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} merchants",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_merchants",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all merchants", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_merchants",
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
                                                                                "find_all_merchants"));
                                                                logger.debug("Find all merchants operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findByActive(FindAllMerchants req) {
                String cacheKey = String.format("merchants:active:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveMerchants")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_active_merchants")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantQueryRepository.findActiveMerchants(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("merchant.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("merchant.page", req.getPage());
                                                                span.setAttribute("merchant.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Active merchants retrieved successfully",
                                                                                MerchantResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} active merchants",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_merchants",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding active merchants", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_merchants",
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
                                                                                "find_active_merchants"));
                                                                logger.debug("Find active merchants operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findByTrashed(FindAllMerchants req) {
                String cacheKey = String.format("merchants:trashed:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<MerchantResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<MerchantResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedMerchants")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_trashed_merchants")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return merchantQueryRepository.findTrashedMerchants(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("merchant.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("merchant.page", req.getPage());
                                                                span.setAttribute("merchant.size", req.getPageSize());

                                                                ApiResponsePagination<List<MerchantResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Trashed merchants retrieved successfully",
                                                                                MerchantResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} trashed merchants",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_merchants",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding trashed merchants", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_merchants",
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
                                                                                "find_trashed_merchants"));
                                                                logger.debug("Find trashed merchants operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<MerchantResponse>> findById(Long merchantId) {
                String cacheKey = "merchant:id:" + merchantId;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                MerchantResponse cachedMerchant = fromJson(cachedJson,
                                                                MerchantResponse.class);
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Merchant retrieved successfully", cachedMerchant));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMerchantById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_merchant_by_id")
                                                        .setAttribute("merchant.id", merchantId.toString())
                                                        .startSpan();

                                        return merchantQueryRepository.findMerchantById(merchantId)
                                                        .chain(merchant -> {
                                                                if (merchant == null) {
                                                                        logger.warn("Merchant not found with id: {}",
                                                                                        merchantId);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Merchant not found");
                                                                        span.setAttribute("merchant.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_merchant_by_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Merchant not found with id: "
                                                                                                        + merchantId);
                                                                }

                                                                span.setAttribute("merchant.found", true);
                                                                span.setAttribute("merchant.name", merchant.getName());

                                                                MerchantResponse merchantResponse = MerchantResponse
                                                                                .from(merchant);

                                                                return redisService
                                                                                .setReactive(cacheKey, toJson(
                                                                                                merchantResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached merchant for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found merchant with id: {} and name: {}",
                                                                                                        merchantId,
                                                                                                        merchant.getName());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_merchant_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Merchant retrieved successfully",
                                                                                                        merchantResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding merchant by id: {}",
                                                                                merchantId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_by_id",
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
                                                                                "find_merchant_by_id"));
                                                                logger.debug("Find merchant by id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<MerchantResponse>> findByApiKey(String apiKey) {
                String cacheKey = "merchant:apikey:" + apiKey;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                MerchantResponse cachedMerchant = fromJson(cachedJson,
                                                                MerchantResponse.class);
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Merchant retrieved successfully", cachedMerchant));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMerchantByApiKey")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_merchant_by_api_key")
                                                        .startSpan();

                                        return merchantQueryRepository.findByApiKey(apiKey)
                                                        .chain(merchant -> {
                                                                if (merchant == null) {
                                                                        logger.warn("Merchant not found with api key: {}",
                                                                                        apiKey);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Merchant not found");
                                                                        span.setAttribute("merchant.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_merchant_by_api_key",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Merchant not found with api key");
                                                                }

                                                                span.setAttribute("merchant.found", true);
                                                                span.setAttribute("merchant.name", merchant.getName());

                                                                MerchantResponse merchantResponse = MerchantResponse
                                                                                .from(merchant);

                                                                return redisService
                                                                                .setReactive(cacheKey, toJson(
                                                                                                merchantResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached merchant for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found merchant with api key and name: {}",
                                                                                                        merchant.getName());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_merchant_by_api_key",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Merchant retrieved successfully",
                                                                                                        merchantResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding merchant by api key", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchant_by_api_key",
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
                                                                                "find_merchant_by_api_key"));
                                                                logger.debug("Find merchant by api key operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<MerchantResponse>>> findByUserId(Long userId) {
                String cacheKey = "merchant:user:" + userId;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<MerchantResponse> cachedMerchants = fromJson(cachedJson,
                                                                new TypeReference<List<MerchantResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Merchants retrieved successfully", cachedMerchants));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findMerchantsByUserId")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "merchant-query-service")
                                                        .setAttribute("operation", "find_merchants_by_user_id")
                                                        .setAttribute("user.id", userId.toString())
                                                        .startSpan();

                                        return merchantQueryRepository.findByUserId(userId)
                                                        .chain(merchants -> {
                                                                List<MerchantResponse> responses = merchants.stream()
                                                                                .map(MerchantResponse::from)
                                                                                .collect(Collectors.toList());

                                                                span.setAttribute("merchants.count", responses.size());
                                                                span.setStatus(StatusCode.OK);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(responses))
                                                                                .map(v -> {
                                                                                        logger.info("Cached merchants for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found {} merchants for user id: {}",
                                                                                                        responses.size(),
                                                                                                        userId);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_merchants_by_user_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Merchants retrieved successfully",
                                                                                                        responses);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding merchants by user id: {}",
                                                                                userId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_merchants_by_user_id",
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
                                                                                "find_merchants_by_user_id"));
                                                                logger.debug("Find merchants by user id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        FindAllMerchants request,
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
