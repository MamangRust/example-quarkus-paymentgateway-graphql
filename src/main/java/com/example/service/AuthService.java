package com.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.config.RedisService;
import com.example.domain.requests.auth.LoginRequest;
import com.example.domain.requests.auth.RegisterRequest;
import com.example.domain.responses.TokenResponse;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.user.UserResponse;
import com.example.entity.RefreshToken;
import com.example.entity.Role;
import com.example.entity.User;
import com.example.exception.InvalidRequestException;
import com.example.exception.ResourceAlreadyExistsException;
import com.example.exception.ResourceNotFoundException;
import com.example.exception.UnauthorizedException;
import com.example.repository.RefreshTokenRepository;
import com.example.repository.RoleRepository;
import com.example.repository.UserRepository;
import com.example.security.SecurityContext;
import com.example.utils.JwtUtil;
import com.example.utils.PasswordUtil;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final long CURRENT_USER_CACHE_TTL_SECONDS = 600;

    @Inject
    UserRepository userRepository;

    @Inject
    RoleRepository roleRepository;

    @Inject
    RefreshTokenRepository refreshTokenRepository;

    @Inject
    PasswordUtil passwordUtil;

    @Inject
    JwtUtil jwtUtil;

    @Inject
    SecurityContext securityContext;

    @Inject
    RedisService redisService;

    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    @Inject
    public AuthService(OpenTelemetry openTelemetry, ObjectMapper objectMapper, RedisService redisService) {
        this.objectMapper = objectMapper;
        this.tracer = openTelemetry.getTracer("auth-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("auth-service");

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

    @WithTransaction
    public Uni<ApiResponse<TokenResponse>> authenticate(LoginRequest loginRequest) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("authenticate")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "auth-service")
                .setAttribute("operation", "login")
                .startSpan();

        logger.info("Authentication attempt for user: {}", loginRequest.getUsernameOrEmail());

        return userRepository.findByUsername(loginRequest.getUsernameOrEmail())
                .chain(foundUser -> {
                    if (foundUser != null) {
                        return Uni.createFrom().item(foundUser);
                    }
                    logger.debug("User not found by username, trying email: {}", loginRequest.getUsernameOrEmail());
                    return userRepository.findByEmail(loginRequest.getUsernameOrEmail());
                })
                .chain(user -> {
                    if (user == null) {
                        logger.warn("Authentication failed - user not found: {}", loginRequest.getUsernameOrEmail());
                        span.setStatus(StatusCode.ERROR, "User not found");
                        span.setAttribute("auth.success", false);
                        span.setAttribute("error.type", "user_not_found");

                        requestsTotal.add(1, Attributes.of(
                                AttributeKey.stringKey("operation"), "login",
                                AttributeKey.stringKey("status"), "failed",
                                AttributeKey.stringKey("error_type"), "user_not_found"));

                        throw new UnauthorizedException("Invalid credentials");
                    }

                    span.setAttribute("user.id", user.getUserId());
                    span.setAttribute("user.username", user.getUsername());

                    boolean passwordMatch = passwordUtil.verifyPassword(loginRequest.getPassword(), user.getPassword());

                    if (!passwordMatch) {
                        logger.warn("Authentication failed - invalid password for user: {}", user.getUsername());
                        span.setStatus(StatusCode.ERROR, "Invalid password");
                        span.setAttribute("auth.success", false);
                        span.setAttribute("error.type", "invalid_password");

                        requestsTotal.add(1, Attributes.of(
                                AttributeKey.stringKey("operation"), "login",
                                AttributeKey.stringKey("status"), "failed",
                                AttributeKey.stringKey("error_type"), "invalid_password"));

                        throw new UnauthorizedException("Invalid credentials");
                    }

                    logger.debug("Password verified for user: {}", user.getUsername());

                    List<String> roleNames = user.getRoles().stream()
                            .map(Role::getRoleName)
                            .collect(Collectors.toList());

                    span.setAttribute("user.roles", String.join(",", roleNames));

                    String accessToken = jwtUtil.generateToken(user.getUsername(), roleNames, user.getUserId());
                    String refreshToken = jwtUtil.generateRefreshToken(user.getUsername(), user.getUserId());

                    logger.debug("Tokens generated for user: {}", user.getUsername());

                    return refreshTokenRepository.findByUserId(user.getUserId())
                            .chain(refreshTokenModel -> {
                                RefreshToken refreshToken2;
                                if (refreshTokenModel != null) {
                                    logger.debug("Updating existing refresh token for user: {}", user.getUsername());
                                    refreshToken2 = refreshTokenModel;
                                    refreshToken2.setToken(refreshToken);
                                    refreshToken2.setExpiration(new Timestamp(
                                            System.currentTimeMillis() + jwtUtil.getRefreshExpirationMs()));
                                    return refreshTokenRepository.persist(refreshToken2).map(v -> refreshToken);
                                } else {
                                    logger.debug("Creating new refresh token for user: {}", user.getUsername());
                                    return refreshTokenRepository.deleteByUserId(user.getUserId())
                                            .chain(v -> {
                                                RefreshToken newRefreshToken = new RefreshToken();
                                                newRefreshToken.setUser(user);
                                                newRefreshToken.setToken(refreshToken);
                                                newRefreshToken.setExpiration(new Timestamp(
                                                        System.currentTimeMillis() + jwtUtil.getRefreshExpirationMs()));
                                                return refreshTokenRepository.persist(newRefreshToken)
                                                        .map(v2 -> refreshToken);
                                            });
                                }
                            })
                            .map(tokenString -> {
                                TokenResponse tokenResponse = TokenResponse.builder()
                                        .access_token(accessToken)
                                        .refresh_token(tokenString)
                                        .build();

                                logger.info("Authentication successful for user: {}", user.getUsername());
                                span.setStatus(StatusCode.OK);
                                span.setAttribute("auth.success", true);

                                requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "login",
                                        AttributeKey.stringKey("status"), "success"));

                                return ApiResponse.success("Login Success", tokenResponse);
                            });
                })
                .onFailure().invoke(e -> {
                    logger.error("Authentication error for user: {}", loginRequest.getUsernameOrEmail(), e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                })
                .eventually(() -> {
                    span.end();
                    double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                    requestDurationSeconds.record(duration, Attributes.of(
                            AttributeKey.stringKey("operation"), "login"));
                    logger.debug("Authentication operation completed in {} seconds", duration);
                });
    }

    @WithTransaction
    public Uni<ApiResponse<UserResponse>> registerUser(RegisterRequest request) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("registerUser")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "auth-service")
                .setAttribute("operation", "register")
                .setAttribute("user.username", request.getUsername())
                .setAttribute("user.email", request.getEmail())
                .startSpan();

        try {
            logger.info("User registration attempt for username: {}, email: {}",
                    request.getUsername(), request.getEmail());

            if (!request.getPassword().equals(request.getConfirmPassword())) {
                logger.warn("Registration failed - passwords do not match for user: {}", request.getUsername());
                span.setStatus(StatusCode.ERROR, "Passwords do not match");
                span.setAttribute("error.type", "password_mismatch");

                requestsTotal.add(1, Attributes.of(
                        AttributeKey.stringKey("operation"), "register",
                        AttributeKey.stringKey("status"), "failed",
                        AttributeKey.stringKey("error_type"), "password_mismatch"));

                throw new InvalidRequestException("Passwords do not match");
            }

            return userRepository.existsByUsername(request.getUsername())
                    .chain(usernameExists -> {
                        if (usernameExists) {
                            logger.warn("Registration failed - username already exists: {}", request.getUsername());
                            span.setStatus(StatusCode.ERROR, "Username already exists");
                            span.setAttribute("error.type", "username_exists");

                            requestsTotal.add(1, Attributes.of(
                                    AttributeKey.stringKey("operation"), "register",
                                    AttributeKey.stringKey("status"), "failed",
                                    AttributeKey.stringKey("error_type"), "username_exists"));

                            throw new ResourceAlreadyExistsException("Username already exists");
                        }
                        return userRepository.existsByEmail(request.getEmail());
                    })
                    .chain(emailExists -> {
                        if (emailExists) {
                            logger.warn("Registration failed - email already exists: {}", request.getEmail());
                            span.setStatus(StatusCode.ERROR, "Email already exists");
                            span.setAttribute("error.type", "email_exists");

                            requestsTotal.add(1, Attributes.of(
                                    AttributeKey.stringKey("operation"), "register",
                                    AttributeKey.stringKey("status"), "failed",
                                    AttributeKey.stringKey("error_type"), "email_exists"));

                            throw new ResourceAlreadyExistsException("Email already exists");
                        }

                        logger.debug("Creating new user entity for: {}", request.getUsername());

                        User user = new User();
                        user.setUsername(request.getUsername());
                        user.setEmail(request.getEmail());
                        user.setFirstname(request.getFirstname());
                        user.setLastname(request.getLastname());
                        user.setPassword(passwordUtil.hashPassword(request.getPassword()));

                        Uni<Set<Role>> rolesUni;
                        if (request.getRoleNames() != null && !request.getRoleNames().isEmpty()) {
                            logger.debug("Assigning requested roles: {}", request.getRoleNames());

                            List<Uni<Role>> roleUnis = request.getRoleNames().stream()
                                    .map(roleRepository::findByRoleName)
                                    .collect(Collectors.toList());

                            rolesUni = Uni.join().all(roleUnis).andFailFast()
                                    .map(roles -> roles.stream().filter(java.util.Objects::nonNull)
                                            .collect(Collectors.toSet()));
                        } else {
                            rolesUni = Uni.createFrom().item(Set.of());
                        }

                        return rolesUni.chain(rolesToAssign -> {
                            if (rolesToAssign.isEmpty()) {
                                logger.debug("No roles specified, assigning default ROLE_ADMIN");
                                return roleRepository.findByRoleName("ROLE_ADMIN")
                                        .map(adminRole -> {
                                            Set<Role> roles = new java.util.HashSet<>();
                                            if (adminRole != null) {
                                                roles.add(adminRole);
                                            }
                                            return roles;
                                        });
                            }
                            return Uni.createFrom().item(rolesToAssign);
                        }).chain(rolesToAssign -> {
                            user.setRoles(rolesToAssign);
                            return userRepository.persist(user)
                                    .map(v -> {
                                        List<String> assignedRoles = rolesToAssign.stream()
                                                .map(Role::getRoleName)
                                                .collect(Collectors.toList());
                                        span.setAttribute("user.roles", String.join(",", assignedRoles));
                                        span.setAttribute("user.id", user.getUserId().toString());

                                        logger.info("User registered successfully: {} with roles: {}",
                                                user.getUsername(), assignedRoles);

                                        UserResponse userResponse = UserResponse.from(user);

                                        span.setStatus(StatusCode.OK);
                                        requestsTotal.add(1, Attributes.of(
                                                AttributeKey.stringKey("operation"), "register",
                                                AttributeKey.stringKey("status"), "success"));

                                        logger.info("User registered. List caches will be refreshed upon expiry.");

                                        return ApiResponse.success("User registered successfully", userResponse);
                                    });
                        });
                    })
                    .onFailure().invoke(e -> {
                        logger.error("Registration error for username: {}", request.getUsername(), e);
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                    })
                    .eventually(() -> {
                        span.end();
                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                        requestDurationSeconds.record(duration, Attributes.of(
                                AttributeKey.stringKey("operation"), "register"));
                        logger.debug("Registration operation completed in {} seconds", duration);
                    });

        } catch (Exception e) {
            logger.error("Registration error for username: {}", request.getUsername(), e);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        }
    }

    @WithTransaction
    public Uni<ApiResponse<TokenResponse>> refreshToken(String token) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("refreshToken")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "auth-service")
                .setAttribute("operation", "refresh_token")
                .startSpan();

        try {
            logger.info("Token refresh attempt");

            if (!jwtUtil.validateToken(token)) {
                logger.warn("Token refresh failed - invalid or expired token");
                span.setStatus(StatusCode.ERROR, "Invalid or expired refresh token");
                span.setAttribute("error.type", "invalid_token");

                requestsTotal.add(1, Attributes.of(
                        AttributeKey.stringKey("operation"), "refresh_token",
                        AttributeKey.stringKey("status"), "failed",
                        AttributeKey.stringKey("error_type"), "invalid_token"));

                throw new UnauthorizedException("Invalid or expired refresh token");
            }

            return refreshTokenRepository.findByToken(token)
                    .chain(refreshTokenEntity -> {
                        if (refreshTokenEntity == null) {
                            logger.warn("Token refresh failed - refresh token not found in database");
                            span.setStatus(StatusCode.ERROR, "Refresh token not found");
                            span.setAttribute("error.type", "token_not_found");

                            requestsTotal.add(1, Attributes.of(
                                    AttributeKey.stringKey("operation"), "refresh_token",
                                    AttributeKey.stringKey("status"), "failed",
                                    AttributeKey.stringKey("error_type"), "token_not_found"));

                            throw new UnauthorizedException("Refresh token not found");
                        }

                        if (refreshTokenEntity.getExpiration().before(new Date())) {
                            logger.warn("Token refresh failed - refresh token has expired");
                            span.setStatus(StatusCode.ERROR, "Refresh token expired");
                            span.setAttribute("error.type", "token_expired");

                            requestsTotal.add(1, Attributes.of(
                                    AttributeKey.stringKey("operation"), "refresh_token",
                                    AttributeKey.stringKey("status"), "failed",
                                    AttributeKey.stringKey("error_type"), "token_expired"));

                            throw new UnauthorizedException("Refresh token has expired");
                        }

                        String username = jwtUtil.getUsernameFromToken(token);
                        span.setAttribute("user.username", username);
                        logger.debug("Refreshing token for user: {}", username);

                        return userRepository.findByUsername(username)
                                .chain(user -> {
                                    if (user == null) {
                                        logger.error("Token refresh failed - user not found: {}", username);
                                        span.setStatus(StatusCode.ERROR, "User not found");
                                        span.setAttribute("error.type", "user_not_found");

                                        requestsTotal.add(1, Attributes.of(
                                                AttributeKey.stringKey("operation"), "refresh_token",
                                                AttributeKey.stringKey("status"), "failed",
                                                AttributeKey.stringKey("error_type"), "user_not_found"));

                                        throw new ResourceNotFoundException(
                                                "User associated with this token not found");
                                    }

                                    span.setAttribute("user.id", user.getUserId());

                                    List<String> roles = user.getRoles().stream()
                                            .map(Role::getRoleName)
                                            .collect(Collectors.toList());

                                    span.setAttribute("user.roles", String.join(",", roles));

                                    String newAccessToken = jwtUtil.generateToken(user.getUsername(), roles,
                                            user.getUserId());
                                    String newRefreshTokenString = jwtUtil.generateRefreshToken(user.getUsername(),
                                            user.getUserId());

                                    refreshTokenEntity.setToken(newRefreshTokenString);
                                    refreshTokenEntity.setExpiration(new Timestamp(
                                            System.currentTimeMillis() + jwtUtil.getRefreshExpirationMs()));

                                    return refreshTokenRepository.persist(refreshTokenEntity)
                                            .map(v -> {
                                                logger.info("Token refreshed successfully for user: {}", username);

                                                TokenResponse tokenResponse = TokenResponse.builder()
                                                        .access_token(newAccessToken)
                                                        .refresh_token(newRefreshTokenString)
                                                        .build();

                                                span.setStatus(StatusCode.OK);
                                                requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "refresh_token",
                                                        AttributeKey.stringKey("status"), "success"));

                                                return ApiResponse.success("Token refreshed successfully",
                                                        tokenResponse);
                                            });
                                });
                    })
                    .onFailure().invoke(e -> {
                        logger.error("Token refresh error", e);
                        span.recordException(e);
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                    })
                    .eventually(() -> {
                        span.end();
                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                        requestDurationSeconds.record(duration, Attributes.of(
                                AttributeKey.stringKey("operation"), "refresh_token"));
                        logger.debug("Token refresh operation completed in {} seconds", duration);
                    });

        } catch (Exception e) {
            logger.error("Token refresh error", e);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        }
    }

    public Uni<ApiResponse<UserResponse>> getCurrentUser() {
        Span span = tracer.spanBuilder("getCurrentUser")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("operation", "get_current_user")
                .startSpan();

        return securityContext.getCurrentUser()
                .chain(user -> {
                    if (user == null) {
                        logger.warn("Get current user failed - user not authenticated");
                        span.setStatus(StatusCode.ERROR, "User not authenticated");
                        throw new UnauthorizedException("User not authenticated");
                    }

                    String cacheKey = "user:" + user.getUserId();
                    String cachedJson = redisService.get(cacheKey);

                    if (cachedJson != null) {
                        logger.info("Cache HIT for current user key: {}", cacheKey);
                        span.addEvent("cache_hit");
                        UserResponse userResponse = fromJson(cachedJson, UserResponse.class);

                        logger.info("Current user retrieved: {}", userResponse.getUsername());
                        span.setAttribute("user.id", userResponse.getId());
                        span.setAttribute("user.username", userResponse.getUsername());
                        span.setStatus(StatusCode.OK);

                        return Uni.createFrom().item(ApiResponse.success("success get current user", userResponse));
                    } else {
                        logger.info("Cache MISS for current user key: {}. Fetching from DB.", cacheKey);
                        span.addEvent("cache_miss");
                        UserResponse userResponse = UserResponse.from(user);

                        return redisService
                                .setWithExpirationReactive(cacheKey, toJson(userResponse),
                                        CURRENT_USER_CACHE_TTL_SECONDS)
                                .map(v -> {
                                    logger.info("Cached current user for key: {}", cacheKey);
                                    logger.info("Current user retrieved: {}", userResponse.getUsername());
                                    span.setAttribute("user.id", userResponse.getId());
                                    span.setAttribute("user.username", userResponse.getUsername());
                                    span.setStatus(StatusCode.OK);

                                    return ApiResponse.success("success get current user", userResponse);
                                });
                    }
                })
                .onFailure().invoke(e -> {
                    logger.error("Error getting current user", e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                })
                .eventually(() -> {
                    span.end();
                });
    }
}