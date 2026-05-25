package com.example.service.impl.role;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.role.FindAllRoles;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.api.PagedResult;
import com.example.domain.responses.api.PaginationMeta;
import com.example.domain.responses.role.RoleResponse;
import com.example.domain.responses.role.RoleResponseDeleteAt;
import com.example.repository.RoleRepository;
import com.example.service.role.RoleQueryService;

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
public class RoleQueryServiceImpl implements RoleQueryService {
        private static final Logger logger = LoggerFactory.getLogger(RoleQueryServiceImpl.class);

        RoleRepository roleRepository;
        RedisService redisService;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        private final ObjectMapper objectMapper;

        private static final long LIST_CACHE_TTL_SECONDS = 300;

        @Inject
        public RoleQueryServiceImpl(RoleRepository roleRepository, OpenTelemetry openTelemetry,
                        RedisService redisService,
                        ObjectMapper objectMapper) {
                this.roleRepository = roleRepository;
                this.redisService = redisService;
                this.objectMapper = objectMapper;
                this.tracer = openTelemetry.getTracer("role-query-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("role-query-service");

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
        public Uni<ApiResponsePagination<List<RoleResponse>>> findAllPaginated(FindAllRoles request) {
                String cacheKey = String.format("roles:all:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<RoleResponse>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<RoleResponse>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findAllRoles")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "role-service")
                                                        .setAttribute("operation", "find_all_roles")
                                                        .startSpan();

                                        int page = request.getPage() - 1;
                                        int size = request.getPageSize();
                                        String search = request.getSearch();

                                        return roleRepository.findRoles(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("role.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("role.page", request.getPage());
                                                                span.setAttribute("role.size", request.getPageSize());

                                                                ApiResponsePagination<List<RoleResponse>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Roles retrieved successfully",
                                                                                RoleResponse::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} roles",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_all_roles",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding all roles", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_all_roles",
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
                                                                                "find_all_roles"));
                                                                logger.debug("Find all roles operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findActivePaginated(FindAllRoles request) {
                String cacheKey = String.format("roles:active:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<RoleResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<RoleResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findActiveRoles")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "role-service")
                                                        .setAttribute("operation", "find_active_roles")
                                                        .startSpan();

                                        int page = request.getPage() - 1;
                                        int size = request.getPageSize();
                                        String search = request.getSearch();

                                        return roleRepository.findActiveRoles(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("role.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("role.page", request.getPage());
                                                                span.setAttribute("role.size", request.getPageSize());

                                                                ApiResponsePagination<List<RoleResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Active roles retrieved successfully",
                                                                                RoleResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} active roles",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_active_roles",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding active roles", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_active_roles",
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
                                                                                "find_active_roles"));
                                                                logger.debug("Find active roles operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findTrashedPaginated(FindAllRoles request) {
                String cacheKey = String.format("roles:trashed:%d:%d:%s", request.getPage(), request.getPageSize(),
                                request.getSearch());

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                ApiResponsePagination<List<RoleResponseDeleteAt>> response = fromJson(
                                                                cachedJson,
                                                                new TypeReference<ApiResponsePagination<List<RoleResponseDeleteAt>>>() {
                                                                });
                                                return Uni.createFrom().item(response);
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findTrashedRoles")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "role-service")
                                                        .setAttribute("operation", "find_trashed_roles")
                                                        .startSpan();

                                        int page = request.getPage() - 1;
                                        int size = request.getPageSize();
                                        String search = request.getSearch();

                                        return roleRepository.findTrashedRoles(search, page, size)
                                                        .chain(pagedResult -> {
                                                                span.setAttribute("role.count",
                                                                                pagedResult.getTotalRecords());
                                                                span.setAttribute("role.page", request.getPage());
                                                                span.setAttribute("role.size", request.getPageSize());

                                                                ApiResponsePagination<List<RoleResponseDeleteAt>> response = buildPaginatedResponse(
                                                                                pagedResult, request,
                                                                                "Trashed roles retrieved successfully",
                                                                                RoleResponseDeleteAt::from);

                                                                return redisService
                                                                                .setWithExpirationReactive(cacheKey,
                                                                                                toJson(response),
                                                                                                LIST_CACHE_TTL_SECONDS)
                                                                                .map(v -> {
                                                                                        logger.info("Cached response for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully retrieved {} trashed roles",
                                                                                                        pagedResult.getTotalRecords());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_trashed_roles",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));
                                                                                        return response;
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding trashed roles", e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_trashed_roles",
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
                                                                                "find_trashed_roles"));
                                                                logger.debug("Find trashed roles operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        @Override
        public Uni<ApiResponse<RoleResponse>> findById(Long id) {
                String cacheKey = "role:" + id;

                return redisService.getReactive(cacheKey)
                                .chain(cachedJson -> {
                                        if (cachedJson != null) {
                                                logger.info("Cache HIT for key: {}", cacheKey);
                                                RoleResponse cachedRole = fromJson(cachedJson, RoleResponse.class);
                                                return Uni.createFrom()
                                                                .item(ApiResponse.success("Role found", cachedRole));
                                        }

                                        logger.info("Cache MISS for key: {}. Fetching from DB.", cacheKey);
                                        long startTime = System.currentTimeMillis();
                                        Span span = tracer.spanBuilder("findRoleById")
                                                        .setSpanKind(SpanKind.SERVER)
                                                        .setAttribute("service.name", "role-service")
                                                        .setAttribute("operation", "find_role_by_id")
                                                        .setAttribute("role.id", id.toString())
                                                        .startSpan();

                                        return roleRepository.findById(id)
                                                        .chain(role -> {
                                                                if (role == null) {
                                                                        logger.warn("Role not found with id: {}", id);
                                                                        span.setStatus(StatusCode.ERROR,
                                                                                        "Role not found");
                                                                        span.setAttribute("role.found", false);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "find_role_by_id",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "failed",
                                                                                        AttributeKey.stringKey(
                                                                                                        "error_type"),
                                                                                        "not_found"));

                                                                        throw new NotFoundException(
                                                                                        "Role not found with id: "
                                                                                                        + id);
                                                                }

                                                                span.setAttribute("role.found", true);
                                                                span.setAttribute("role.name", role.getRoleName());

                                                                RoleResponse roleResponse = RoleResponse.from(role);

                                                                return redisService
                                                                                .setReactive(cacheKey,
                                                                                                toJson(roleResponse))
                                                                                .map(v -> {
                                                                                        logger.info("Cached role for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully found role with id: {} and name: {}",
                                                                                                        id,
                                                                                                        role.getRoleName());
                                                                                        span.setStatus(StatusCode.OK);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "find_role_by_id",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Role found",
                                                                                                        roleResponse);
                                                                                });
                                                        })
                                                        .onFailure().invoke(e -> {
                                                                logger.error("Error finding role by id: {}", id, e);
                                                                span.recordException(e);
                                                                span.setStatus(StatusCode.ERROR, e.getMessage());

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "find_role_by_id",
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
                                                                                "find_role_by_id"));
                                                                logger.debug("Find role by id operation completed in {} seconds",
                                                                                duration);
                                                        });
                                });
        }

        private <T, R> ApiResponsePagination<List<R>> buildPaginatedResponse(
                        PagedResult<T> pagedResult,
                        FindAllRoles request,
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
