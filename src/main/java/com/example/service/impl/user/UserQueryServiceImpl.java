package com.example.service.impl.user;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.user.FindAllUsers;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.user.UserResponse;
import com.example.domain.responses.user.UserResponseDeleteAt;
import com.example.repository.UserRepository;
import com.example.service.user.UserQueryService;

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
public class UserQueryServiceImpl implements UserQueryService {
        private static final Logger logger = LoggerFactory.getLogger(UserQueryServiceImpl.class);

        UserRepository userRepository;
        RedisService redisService;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private final ObjectMapper objectMapper;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public UserQueryServiceImpl(UserRepository userRepository, OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.userRepository = userRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("user-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("user-query-service");

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
        public Uni<ApiResponsePagination<List<UserResponse>>> findAllPaginated(FindAllUsers request) {
                String cacheKey = String.format("users:all:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<UserResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<UserResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllUsers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "user-service")
                                                        .setAttribute("operation", "find_all_users")
                                                        .startSpan();

                                        return userRepository.findUsers(request)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("user.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("user.page", request.getPage());
                                                                span.setAttribute("user.size", request.getPageSize());

                                                                ApiResponsePagination<List<UserResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Users retrieved successfully",
                                                                                UserResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} users",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_users",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all users", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_users",
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
                                                                                "find_all_users"));
                                                                logger.debug("Find all users operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findActivePaginated(FindAllUsers request) {
                String cacheKey = String.format("users:active:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<UserResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<UserResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveUsers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "user-service")
                                                        .setAttribute("operation", "find_active_users")
                                                        .startSpan();

                                        return userRepository.findActiveUsers(request)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("user.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("user.page", request.getPage());
                                                                span.setAttribute("user.size", request.getPageSize());

                                                                ApiResponsePagination<List<UserResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Active users retrieved successfully",
                                                                                UserResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} active users",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_users",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding active users", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_users",
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
                                                                                "find_active_users"));
                                                                logger.debug("Find active users operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findTrashedPaginated(FindAllUsers request) {
                String cacheKey = String.format("users:trashed:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<UserResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<UserResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedUsers")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "user-service")
                                                        .setAttribute("operation", "find_trashed_users")
                                                        .startSpan();

                                        return userRepository.findTrashedUsers(request)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("user.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("user.page", request.getPage());
                                                                span.setAttribute("user.size", request.getPageSize());

                                                                ApiResponsePagination<List<UserResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Trashed users retrieved successfully",
                                                                                UserResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} trashed users",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_users",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding trashed users", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_users",
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
                                                                                "find_trashed_users"));
                                                                logger.debug("Find trashed users operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<UserResponse>> findById(Long id) {
                String cacheKey = "user:" + id;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                UserResponse cachedUser = fromJson(cachedJson, UserResponse.class);
                                                return Uni.createFrom()
                                                                .item(ApiResponse.success("User found", cachedUser));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findUserById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "user-service")
                                                        .setAttribute("operation", "find_user_by_id")
                                                        .setAttribute("user.id", id.toString())
                                                        .startSpan();

                                        return userRepository.findById(id)
                                                        .chain(user -> {
                                                                if (user == null) {
                                                                        logger.warn("User not found with id: {}", id);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "User not found");
                                                                        span.setAttribute("user.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_user_by_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "User not found with id: "
                                                                                                        + id);
                                                                }

                                                                span.setAttribute("user.found", true);
                                                                span.setAttribute("user.username", user.getUsername());

                                                                UserResponse userResponse = UserResponse.from(user);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(userResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached user for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found user with id: {} and username: {}",
                                                                                                        id,
                                                                                                        user.getUsername());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_user_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "User found",
                                                                                                        userResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding user by id: {}", id, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_user_by_id",
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
                                                                                "find_user_by_id"));
                                                                logger.debug("Find user by id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        FindAllUsers request,
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
