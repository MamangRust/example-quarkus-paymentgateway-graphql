package com.example.service.impl.topup;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.topup.FindAllTopups;
import com.example.domain.requests.topup.FindAllTopupsByCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.topup.TopupResponse;
import com.example.domain.responses.topup.TopupResponseDeleteAt;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.topup.TopupQueryRepository;
import com.example.service.topup.TopupQueryService;

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
public class TopupQueryServiceImpl implements TopupQueryService {
        private static final Logger logger = LoggerFactory.getLogger(TopupQueryServiceImpl.class);

        private final TopupQueryRepository topupQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public TopupQueryServiceImpl(TopupQueryRepository topupQueryRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.topupQueryRepository = topupQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("topup-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("topup-query-service");

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
        public Uni<ApiResponsePagination<List<TopupResponse>>> findAll(FindAllTopups req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                String cacheKey = String.format("topups:all:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TopupResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TopupResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllTopups")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_all_topups")
                                                        .startSpan();

                                        return topupQueryRepository.findTopups(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("topup.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("topup.page", req.getPage());
                                                                span.setAttribute("topup.size", req.getPageSize());

                                                                ApiResponsePagination<List<TopupResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Found " + pagedResult.getData().size()
                                                                                                + " topups",
                                                                                TopupResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_topups",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding all topups", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_topups",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>("error",
                                                                                "Failed to fetch all topups",
                                                                                Collections.emptyList(), null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_topups"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<TopupResponse>>> findAllByCardNumber(FindAllTopupsByCardNumber req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String cardNumber = req.getCardNumber();
                String search = req.getSearch() != null ? req.getSearch() : "";

                String cacheKey = String.format("topups:card-num:%s:%d:%d:%s", cardNumber, page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TopupResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TopupResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllTopupsByCardNumber")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_all_topups_by_card_number")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .startSpan();

                                        return topupQueryRepository.findTopupByCard(cardNumber, search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("topup.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("topup.page", req.getPage());
                                                                span.setAttribute("topup.size", req.getPageSize());

                                                                ApiResponsePagination<List<TopupResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Found " + pagedResult.getData().size()
                                                                                                + " topups for card="
                                                                                                + cardNumber,
                                                                                TopupResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_topups_by_card_number",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding topups for card={}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_topups_by_card_number",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>("error",
                                                                                "Failed to fetch topups for card="
                                                                                                + cardNumber,
                                                                                Collections.emptyList(), null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_topups_by_card_number"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findActive(FindAllTopups req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                String cacheKey = String.format("topups:active:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TopupResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TopupResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveTopups")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_active_topups")
                                                        .startSpan();

                                        return topupQueryRepository.findActiveTopups(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("topup.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("topup.page", req.getPage());
                                                                span.setAttribute("topup.size", req.getPageSize());

                                                                ApiResponsePagination<List<TopupResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Found " + pagedResult.getData().size()
                                                                                                + " active topups",
                                                                                TopupResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_topups",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding active topups", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_topups",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>("error",
                                                                                "Failed to fetch active topups",
                                                                                Collections.emptyList(), null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_topups"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findTrashed(FindAllTopups req) {
                int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                int pageSize = req.getPageSize() > 0 ? req.getPageSize() : 10;
                String search = req.getSearch() != null ? req.getSearch() : "";

                String cacheKey = String.format("topups:trashed:%d:%d:%s", page, pageSize, search);

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<TopupResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<TopupResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedTopups")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_trashed_topups")
                                                        .startSpan();

                                        return topupQueryRepository.findTrashedTopups(search, page, pageSize)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("topup.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("topup.page", req.getPage());
                                                                span.setAttribute("topup.size", req.getPageSize());

                                                                ApiResponsePagination<List<TopupResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req.getPage(),
                                                                                req.getPageSize(),
                                                                                "Found " + pagedResult.getData().size()
                                                                                                + " trashed topups",
                                                                                TopupResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_topups",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding trashed topups", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_topups",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponsePagination<>("error",
                                                                                "Failed to fetch trashed topups",
                                                                                Collections.emptyList(), null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_topups"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<List<TopupResponse>>> findByCard(String cardNumber) {
                String cacheKey = "topups:card:" + cardNumber;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                List<TopupResponse> cachedTopups = fromJson(cachedJson,
                                                                new TypeReference<List<TopupResponse>>() {
                                                                });
                                                return Uni.createFrom().item(ApiResponse.success(
                                                                "Found " + cachedTopups.size() + " topups for card="
                                                                                + cardNumber,
                                                                cachedTopups));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTopupsByCard")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_topups_by_card")
                                                        .setAttribute("cardNumber", cardNumber)
                                                        .startSpan();

                                        return topupQueryRepository.findByCardNumber(cardNumber)
                                                        .chain(topups -> {
                                                                List<TopupResponse> responseList = topups.stream()
                                                                                .map(TopupResponse::from)
                                                                                .collect(Collectors.toList());

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(responseList),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_topups_by_card",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found " + responseList
                                                                                                                        .size()
                                                                                                                        + " topups for card="
                                                                                                                        + cardNumber,
                                                                                                        responseList);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding topups for card={}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_topups_by_card",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch topups for card="
                                                                                                + cardNumber,
                                                                                Collections.emptyList());
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_topups_by_card"));
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<TopupResponse>> findById(Long topupId) {
                String cacheKey = "topup:id:" + topupId;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                TopupResponse cachedTopup = fromJson(cachedJson, TopupResponse.class);
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Found topup id=" + topupId, cachedTopup));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTopupById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "topup-query-service")
                                                        .setAttribute("operation", "find_topup_by_id")
                                                        .setAttribute("topupId", String.valueOf(topupId))
                                                        .startSpan();

                                        return topupQueryRepository.findTopupById(topupId)
                                                        .chain(topup -> {
                                                                if (topup == null) {
                                                                        logger.error("Topup not found id={}", topupId);
                                                                        throw new ResourceNotFoundException(
                                                                                        "Topup not found");
                                                                }

                                                                TopupResponse response = TopupResponse.from(topup);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_topup_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Found topup id="
                                                                                                                        + topupId,
                                                                                                        response);
                                                                                });
                                                        })
                                                        .onFailure().recoverWithItem(e -> {
                                                                logger.error("Error finding topup by id: {}", topupId,
                                                                                e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_topup_by_id",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                e.getClass().getSimpleName()));

                                                                return new ApiResponse<>("error",
                                                                                "Failed to fetch topup id=" + topupId,
                                                                                null);
                                                        })
                                                        .eventually(() -> {
                                                                span.end();
                                                                double duration = (System.currentTimeMillis()
                                                                                - startTime) / 1000.0;
                                                                requestDurationSeconds.record(duration, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_topup_by_id"));
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        int reqPage,
                        int reqSize,
                        String successMessage,
                        Function<T, R> mapper) {

                List<R> data = pagedResult.getData().stream()
                                .map(mapper)
                                .collect(Collectors.toList());

                int totalRecords = pagedResult.getTotalRecords();
                int size = reqSize > 0 ? reqSize : 1;
                int totalPages = (int) Math.ceil((double) totalRecords / size);

                PaginationMeta pagination = new PaginationMeta(reqPage, size, totalPages, totalRecords);

                return new ApiResponsePagination<>("success", successMessage, data, pagination);
        }
}
