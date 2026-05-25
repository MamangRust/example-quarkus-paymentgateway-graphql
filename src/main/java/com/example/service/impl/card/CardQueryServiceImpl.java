package com.example.service.impl.card;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.card.FindAllCards;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.card.CardResponse;
import com.example.domain.responses.card.CardResponseDeleteAt;
import com.example.repository.card.CardQueryRepository;
import com.example.service.card.CardQueryService;

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
public class CardQueryServiceImpl implements CardQueryService {
        private static final Logger logger = LoggerFactory.getLogger(CardQueryServiceImpl.class);

        private final CardQueryRepository cardQueryRepository;
        private final RedisService redisService;
        private final ObjectMapper objectMapper;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public CardQueryServiceImpl(CardQueryRepository cardQueryRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.cardQueryRepository = cardQueryRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("card-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-query-service");

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
        public Uni<ApiResponsePagination<List<CardResponse>>> findAll(FindAllCards req) {
                String cacheKey = String.format("cards:all:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<CardResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<CardResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllCards")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_all_cards")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return cardQueryRepository.findCards(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("card.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("card.page", req.getPage());
                                                                span.setAttribute("card.size", req.getPageSize());

                                                                ApiResponsePagination<List<CardResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Cards retrieved successfully",
                                                                                CardResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} cards",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_cards",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all cards", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_cards",
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
                                                                                "find_all_cards"));
                                                                logger.debug("Find all cards operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findByActive(FindAllCards req) {
                String cacheKey = String.format("cards:active:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<CardResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<CardResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveCards")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_active_cards")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return cardQueryRepository.findActiveCards(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("card.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("card.page", req.getPage());
                                                                span.setAttribute("card.size", req.getPageSize());

                                                                ApiResponsePagination<List<CardResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Active cards retrieved successfully",
                                                                                CardResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} active cards",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_cards",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding active cards", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_cards",
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
                                                                                "find_active_cards"));
                                                                logger.debug("Find active cards operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findByTrashed(FindAllCards req) {
                String cacheKey = String.format("cards:trashed:%d:%d:%s", req.getPage(), req.getPageSize(),
                                req.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<CardResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<CardResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedCards")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_trashed_cards")
                                                        .startSpan();

                                        int page = req.getPage() > 0 ? req.getPage() - 1 : 0;
                                        int size = req.getPageSize() > 0 ? req.getPageSize() : 10;
                                        String search = (req.getSearch() != null && !req.getSearch().isEmpty())
                                                        ? req.getSearch()
                                                        : null;

                                        return cardQueryRepository.findTrashedCards(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("card.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("card.page", req.getPage());
                                                                span.setAttribute("card.size", req.getPageSize());

                                                                ApiResponsePagination<List<CardResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, req,
                                                                                "Trashed cards retrieved successfully",
                                                                                CardResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} trashed cards",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_cards",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding trashed cards", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_cards",
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
                                                                                "find_trashed_cards"));
                                                                logger.debug("Find trashed cards operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<CardResponse>> findById(Long cardId) {
                String cacheKey = "card:id:" + cardId;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                CardResponse cachedCard = fromJson(cachedJson, CardResponse.class);
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Card retrieved successfully", cachedCard));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findCardById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_card_by_id")
                                                        .setAttribute("card.id", cardId.toString())
                                                        .startSpan();

                                        return cardQueryRepository.findCardById(cardId)
                                                        .chain(card -> {
                                                                if (card == null) {
                                                                        logger.warn("Card not found with id: {}",
                                                                                        cardId);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Card not found");
                                                                        span.setAttribute("card.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_card_by_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Card not found with id: "
                                                                                                        + cardId);
                                                                }

                                                                span.setAttribute("card.found", true);
                                                                span.setAttribute("card.number", card.getCardNumber());

                                                                CardResponse cardResponse = CardResponse.from(card);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(cardResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached card for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found card with id: {} and number: {}",
                                                                                                        cardId,
                                                                                                        card.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_card_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Card retrieved successfully",
                                                                                                        cardResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding card by id: {}", cardId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_card_by_id",
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
                                                                                "find_card_by_id"));
                                                                logger.debug("Find card by id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<CardResponse>> findByUserId(Long userId) {
                String cacheKey = "card:user:" + userId;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                CardResponse cachedCard = fromJson(cachedJson, CardResponse.class);
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Card retrieved successfully", cachedCard));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findCardByUserId")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_card_by_user_id")
                                                        .setAttribute("user.id", userId.toString())
                                                        .startSpan();

                                        return cardQueryRepository.findCardByUserId(userId)
                                                        .chain(card -> {
                                                                if (card == null) {
                                                                        logger.warn("Card not found for user id: {}",
                                                                                        userId);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Card not found");
                                                                        span.setAttribute("card.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_card_by_user_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Card not found for user id: "
                                                                                                        + userId);
                                                                }

                                                                span.setAttribute("card.found", true);
                                                                span.setAttribute("card.number", card.getCardNumber());

                                                                CardResponse cardResponse = CardResponse.from(card);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(cardResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached card for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found card for user id: {} and number: {}",
                                                                                                        userId,
                                                                                                        card.getCardNumber());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_card_by_user_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Card retrieved successfully",
                                                                                                        cardResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding card by user id: {}",
                                                                                userId, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_card_by_user_id",
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
                                                                                "find_card_by_user_id"));
                                                                logger.debug("Find card by user id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<CardResponse>> findByCardNumber(String cardNumber) {
                String cacheKey = "card:number:" + cardNumber;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                CardResponse cachedCard = fromJson(cachedJson, CardResponse.class);
                                                return Uni.createFrom().item(ApiResponse
                                                                .success("Card retrieved successfully", cachedCard));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findCardByCardNumber")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "card-query-service")
                                                        .setAttribute("operation", "find_card_by_card_number")
                                                        .setAttribute("card.number", cardNumber)
                                                        .startSpan();

                                        return cardQueryRepository.findCardByCardNumber(cardNumber)
                                                        .chain(card -> {
                                                                if (card == null) {
                                                                        logger.warn("Card not found with card number: {}",
                                                                                        cardNumber);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Card not found");
                                                                        span.setAttribute("card.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_card_by_card_number",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Card not found with card number: "
                                                                                                        + cardNumber);
                                                                }

                                                                span.setAttribute("card.found", true);
                                                                span.setAttribute("card.number", card.getCardNumber());

                                                                CardResponse cardResponse = CardResponse.from(card);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(cardResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached card for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found card with card number: {}",
                                                                                                        cardNumber);
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_card_by_card_number",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Card retrieved successfully",
                                                                                                        cardResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding card by card number: {}",
                                                                                cardNumber, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_card_by_card_number",
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
                                                                                "find_card_by_card_number"));
                                                                logger.debug("Find card by card number operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        FindAllCards request,
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
