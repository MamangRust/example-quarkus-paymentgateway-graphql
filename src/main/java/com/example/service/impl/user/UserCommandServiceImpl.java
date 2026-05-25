package com.example.service.impl.user;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.auth.RegisterRequest;
import com.example.domain.requests.user.UpdateUserRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.user.UserResponse;
import com.example.domain.responses.user.UserResponseDeleteAt;
import com.example.entity.Role;
import com.example.entity.User;
import com.example.exception.InvalidRequestException;
import com.example.exception.ResourceAlreadyExistsException;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.RoleRepository;
import com.example.repository.UserRepository;
import com.example.service.user.UserCommandService;
import com.example.utils.PasswordUtil;

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
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;

@ApplicationScoped
public class UserCommandServiceImpl implements UserCommandService {
        private static final Logger logger = LoggerFactory.getLogger(UserCommandServiceImpl.class);

        UserRepository userRepository;
        RoleRepository roleRepository;
        PasswordUtil passwordUtil;
        RedisService redisService;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        @Inject
        public UserCommandServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                        PasswordUtil passwordUtil,
                        OpenTelemetry openTelemetry, RedisService redisService) {
                this.userRepository = userRepository;
                this.roleRepository = roleRepository;
                this.passwordUtil = passwordUtil;
                this.redisService = redisService;
                this.tracer = openTelemetry.getTracer("user-command-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("user-command-service");

                this.requestsTotal = meter.counterBuilder("requests_total")
                                .setDescription("Total number of requests")
                                .build();
                this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                                .setDescription("Request duration in seconds")
                                .setUnit("s")
                                .build();
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<UserResponse>> createUser(RegisterRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("createUser")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "create_user")
                                .setAttribute("user.username", request.getUsername())
                                .setAttribute("user.email", request.getEmail())
                                .startSpan();

                logger.info("Creating new user with username: {}", request.getUsername());

                if (!request.getPassword().equals(request.getConfirmPassword())) {
                        logger.warn("User creation failed - passwords do not match for username: {}",
                                        request.getUsername());
                        span.setStatus(StatusCode.ERROR, "Passwords do not match");
                        span.setAttribute("user.create.success", false);

                        requestsTotal.add(1, Attributes.of(
                                        AttributeKey.stringKey("operation"), "create_user",
                                        AttributeKey.stringKey("status"), "failed",
                                        AttributeKey.stringKey("error_type"), "invalid_request"));

                        throw new InvalidRequestException("Passwords do not match");
                }

                return userRepository.existsByUsername(request.getUsername())
                                .chain(usernameExists -> {
                                        if (usernameExists) {
                                                logger.warn("User creation failed - username already exists: {}",
                                                                request.getUsername());
                                                span.setStatus(StatusCode.ERROR, "Username already exists");
                                                span.setAttribute("user.create.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "create_user",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"),
                                                                "already_exists"));

                                                throw new ResourceAlreadyExistsException("Username already exists");
                                        }
                                        return userRepository.existsByEmail(request.getEmail());
                                })
                                .chain(emailExists -> {
                                        if (emailExists) {
                                                logger.warn("User creation failed - email already exists: {}",
                                                                request.getEmail());
                                                span.setStatus(StatusCode.ERROR, "Email already exists");
                                                span.setAttribute("user.create.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "create_user",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"),
                                                                "already_exists"));

                                                throw new ResourceAlreadyExistsException("Email already exists");
                                        }

                                        User user = new User();
                                        user.setUsername(request.getUsername());
                                        user.setEmail(request.getEmail());
                                        user.setFirstname(request.getFirstname());
                                        user.setLastname(request.getLastname());
                                        user.setPassword(passwordUtil.hashPassword(request.getPassword()));

                                        Uni<Set<Role>> rolesUni;
                                        if (request.getRoleNames() != null && !request.getRoleNames().isEmpty()) {
                                                List<Uni<Role>> roleUnis = request.getRoleNames().stream()
                                                                .map(roleRepository::findByRoleName)
                                                                .collect(Collectors.toList());
                                                rolesUni = Uni.join().all(roleUnis).andFailFast()
                                                                .map(roles -> roles.stream()
                                                                                .filter(java.util.Objects::nonNull)
                                                                                .collect(Collectors.toSet()));
                                        } else {
                                                rolesUni = Uni.createFrom().item(Set.of());
                                        }

                                        return rolesUni.chain(rolesToAssign -> {
                                                if (rolesToAssign.isEmpty()) {
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
                                                                        span.setAttribute("user.id", user.getUserId());
                                                                        span.setAttribute("user.create.success", true);

                                                                        UserResponse userResponse = UserResponse
                                                                                        .from(user);

                                                                        logger.info("Successfully created user with id: {} and username: {}",
                                                                                        user.getUserId(),
                                                                                        user.getUsername());
                                                                        span.setStatus(StatusCode.OK);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "create_user",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "success"));

                                                                        logger.info("User created. List caches will be refreshed upon expiry.");

                                                                        return ApiResponse.success(
                                                                                        "User registered successfully",
                                                                                        userResponse);
                                                                });
                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error creating user with username: {}", request.getUsername(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_user",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_user"));
                                        logger.debug("Create user operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<UserResponse>> updateUser(@Valid UpdateUserRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("updateUser")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "update_user")
                                .setAttribute("user.id", request.getId().toString())
                                .startSpan();

                logger.info("Updating user with id: {}", request.getId());

                return userRepository.findById(request.getId())
                                .chain(existingUser -> {
                                        if (existingUser == null) {
                                                logger.warn("User update failed - user not found with id: {}",
                                                                request.getId());
                                                span.setStatus(StatusCode.ERROR, "User not found");
                                                span.setAttribute("user.update.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "update_user",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new ResourceNotFoundException(
                                                                "User not found with id: " + request.getId());
                                        }

                                        Uni<Void> checkUsernameFlow;
                                        if (request.getUsername() != null
                                                        && !request.getUsername().equals(existingUser.getUsername())) {
                                                checkUsernameFlow = userRepository
                                                                .existsByUsername(request.getUsername())
                                                                .map(exists -> {
                                                                        if (exists) {
                                                                                logger.warn("User update failed - username '{}' already in use",
                                                                                                request.getUsername());
                                                                                span.setStatus(StatusCode.ERROR,
                                                                                                "Username already in use");
                                                                                span.setAttribute("user.update.success",
                                                                                                false);

                                                                                requestsTotal.add(1, Attributes.of(
                                                                                                AttributeKey.stringKey(
                                                                                                                "operation"),
                                                                                                "update_user",
                                                                                                AttributeKey.stringKey(
                                                                                                                "status"),
                                                                                                "failed",
                                                                                                AttributeKey.stringKey(
                                                                                                                "error_type"),
                                                                                                "already_exists"));

                                                                                throw new ResourceAlreadyExistsException(
                                                                                                "Username '" + request
                                                                                                                .getUsername()
                                                                                                                + "' is already in use");
                                                                        }
                                                                        existingUser.setUsername(request.getUsername());
                                                                        return null;
                                                                });
                                        } else {
                                                checkUsernameFlow = Uni.createFrom().nullItem();
                                        }

                                        return checkUsernameFlow.chain(v -> {
                                                Uni<Void> checkEmailFlow;
                                                if (request.getEmail() != null && !request.getEmail()
                                                                .equals(existingUser.getEmail())) {
                                                        checkEmailFlow = userRepository
                                                                        .existsByEmail(request.getEmail())
                                                                        .map(exists -> {
                                                                                if (exists) {
                                                                                        logger.warn("User update failed - email '{}' already in use",
                                                                                                        request.getEmail());
                                                                                        span.setStatus(StatusCode.ERROR,
                                                                                                        "Email already in use");
                                                                                        span.setAttribute(
                                                                                                        "user.update.success",
                                                                                                        false);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "update_user",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "failed",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "error_type"),
                                                                                                                        "already_exists"));

                                                                                        throw new ResourceAlreadyExistsException(
                                                                                                        "Email '" + request
                                                                                                                        .getEmail()
                                                                                                                        + "' is already in use");
                                                                                }
                                                                                existingUser.setEmail(
                                                                                                request.getEmail());
                                                                                return null;
                                                                        });
                                                } else {
                                                        checkEmailFlow = Uni.createFrom().nullItem();
                                                }

                                                return checkEmailFlow;
                                        }).chain(v -> {
                                                if (request.getPassword() != null) {
                                                        if (!request.getPassword()
                                                                        .equals(request.getConfirmPassword())) {
                                                                logger.warn("User update failed - passwords do not match for user id: {}",
                                                                                request.getId());
                                                                span.setStatus(StatusCode.ERROR,
                                                                                "Passwords do not match");
                                                                span.setAttribute("user.update.success", false);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "update_user",
                                                                                AttributeKey.stringKey("status"),
                                                                                "failed",
                                                                                AttributeKey.stringKey("error_type"),
                                                                                "invalid_request"));

                                                                throw new InvalidRequestException(
                                                                                "Passwords do not match");
                                                        }
                                                        existingUser.setPassword(passwordUtil
                                                                        .hashPassword(request.getPassword()));
                                                }

                                                if (request.getFirstname() != null) {
                                                        existingUser.setFirstname(request.getFirstname());
                                                }
                                                if (request.getLastname() != null) {
                                                        existingUser.setLastname(request.getLastname());
                                                }

                                                Uni<Void> rolesFlow;
                                                if (request.getRoleNames() != null) {
                                                        List<Uni<Role>> roleUnis = request.getRoleNames().stream()
                                                                        .map(roleName -> roleRepository
                                                                                        .findByRoleName(roleName)
                                                                                        .onItem().ifNull()
                                                                                        .failWith(() -> new ResourceNotFoundException(
                                                                                                        "Role '" + roleName
                                                                                                                        + "' not found")))
                                                                        .collect(Collectors.toList());
                                                        rolesFlow = Uni.join().all(roleUnis).andFailFast()
                                                                        .map(roles -> {
                                                                                existingUser.getRoles().clear();
                                                                                existingUser.getRoles().addAll(roles);
                                                                                return null;
                                                                        });
                                                } else {
                                                        rolesFlow = Uni.createFrom().nullItem();
                                                }

                                                return rolesFlow.chain(v3 -> {
                                                        existingUser.setUpdatedAt(
                                                                        Timestamp.valueOf(LocalDateTime.now()));
                                                        return userRepository.persist(existingUser)
                                                                        .chain(v4 -> {
                                                                                UserResponse userResponse = UserResponse
                                                                                                .from(existingUser);
                                                                                String cacheKey = "user:"
                                                                                                + request.getId();

                                                                                return redisService.deleteReactive(
                                                                                                cacheKey)
                                                                                                .map(v5 -> {
                                                                                                        logger.info("Invalidated cache for key: {}",
                                                                                                                        cacheKey);
                                                                                                        logger.info("Successfully updated user with id: {}",
                                                                                                                        request.getId());
                                                                                                        span.setStatus(StatusCode.OK);
                                                                                                        span.setAttribute(
                                                                                                                        "user.update.success",
                                                                                                                        true);

                                                                                                        requestsTotal.add(
                                                                                                                        1,
                                                                                                                        Attributes.of(
                                                                                                                                        AttributeKey.stringKey(
                                                                                                                                                        "operation"),
                                                                                                                                        "update_user",
                                                                                                                                        AttributeKey.stringKey(
                                                                                                                                                        "status"),
                                                                                                                                        "success"));

                                                                                                        return ApiResponse
                                                                                                                        .success("User updated successfully",
                                                                                                                                        userResponse);
                                                                                                });
                                                                        });
                                                });
                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error updating user with id: {}", request.getId(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_user",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_user"));
                                        logger.debug("Update user operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<UserResponseDeleteAt>> trashed(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("trashUser")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "trash_user")
                                .setAttribute("user.id", id.toString())
                                .startSpan();

                logger.info("Trashing user with id: {}", id);

                return userRepository.trash(id)
                                .chain(trashedUser -> {
                                        if (trashedUser == null) {
                                                logger.warn("User trash failed - user not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "User not found");
                                                span.setAttribute("user.trash.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "trash_user",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new ResourceNotFoundException(
                                                                "Trashed user not found with id: " + id);
                                        }

                                        span.setAttribute("user.username", trashedUser.getUsername());
                                        span.setAttribute("user.trash.success", true);

                                        UserResponseDeleteAt userResponseDeleteAt = UserResponseDeleteAt
                                                        .from(trashedUser);
                                        String cacheKey = "user:" + id;

                                        return redisService.deleteReactive(cacheKey)
                                                        .map(v -> {
                                                                logger.info("Invalidated cache for key: {}", cacheKey);
                                                                logger.info("Successfully trashed user with id: {}",
                                                                                id);
                                                                span.setStatus(StatusCode.OK);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "trash_user",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success("User trashed successfully",
                                                                                userResponseDeleteAt);
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error trashing user with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_user",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_user"));
                                        logger.debug("Trash user operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<UserResponseDeleteAt>> restore(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreUser")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "restore_user")
                                .setAttribute("user.id", id.toString())
                                .startSpan();

                logger.info("Restoring user with id: {}", id);

                return userRepository.restore(id)
                                .chain(restoredUser -> {
                                        if (restoredUser == null) {
                                                logger.warn("User restore failed - user not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "User not found");
                                                span.setAttribute("user.restore.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "restore_user",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new ResourceNotFoundException(
                                                                "Restore user not found with id: " + id);
                                        }

                                        span.setAttribute("user.username", restoredUser.getUsername());
                                        span.setAttribute("user.restore.success", true);

                                        UserResponseDeleteAt userResponseDeleteAt = UserResponseDeleteAt
                                                        .from(restoredUser);
                                        String cacheKey = "user:" + id;

                                        return redisService.deleteReactive(cacheKey)
                                                        .map(v -> {
                                                                logger.info("Invalidated cache for key: {}", cacheKey);
                                                                logger.info("Successfully restored user with id: {}",
                                                                                id);
                                                                span.setStatus(StatusCode.OK);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "restore_user",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success(
                                                                                "User restored successfully",
                                                                                userResponseDeleteAt);
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error restoring user with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_user",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_user"));
                                        logger.debug("Restore user operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> deletePermanent(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteUserPermanent")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "delete_user_permanent")
                                .setAttribute("user.id", id.toString())
                                .startSpan();

                logger.info("Permanently deleting user with id: {}", id);

                return userRepository.findById(id)
                                .chain(userToDelete -> {
                                        if (userToDelete == null) {
                                                logger.warn("Permanent delete failed - user not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "User not found");
                                                span.setAttribute("user.delete.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"),
                                                                "delete_user_permanent",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new ResourceNotFoundException("User not found with id: " + id);
                                        }

                                        span.setAttribute("user.username", userToDelete.getUsername());

                                        return userRepository.deletePermanent(id)
                                                        .chain(v -> {
                                                                String cacheKey = "user:" + id;
                                                                return redisService.deleteReactive(cacheKey)
                                                                                .map(v2 -> {
                                                                                        logger.info("Invalidated cache for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully permanently deleted user with id: {}",
                                                                                                        id);
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        span.setAttribute(
                                                                                                        "user.delete.success",
                                                                                                        true);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "delete_user_permanent",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "User deleted permanently");
                                                                                });
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error permanently deleting user with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_user_permanent",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_user_permanent"));
                                        logger.debug("Permanent delete user operation completed in {} seconds",
                                                        duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> restoreAllTrashedUsers() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreAllTrashedUsers")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "restore_all_trashed_users")
                                .startSpan();

                logger.info("Restoring all trashed users");

                return userRepository.restoreAllDeleted()
                                .map(v -> {
                                        logger.warn("All trashed users restored. Caches will be refreshed upon expiry or next access.");
                                        logger.info("Successfully restored all trashed users");
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_users",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success(
                                                        "All trashed users have been restored successfully");
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error restoring all trashed users", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_users",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_users"));
                                        logger.debug("Restore all trashed users operation completed in {} seconds",
                                                        duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> deleteAllTrashedUsers() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteAllTrashedUsers")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "user-service")
                                .setAttribute("operation", "delete_all_trashed_users")
                                .startSpan();

                logger.info("Permanently deleting all trashed users");

                return userRepository.deleteAllDeleted()
                                .map(v -> {
                                        logger.warn("All trashed users deleted. Caches will be refreshed upon expiry or next access.");
                                        logger.info("Successfully deleted all trashed users");
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_trashed_users",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success("All trashed users have been deleted permanently");
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error deleting all trashed users", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_trashed_users",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "delete_all_trashed_users"));
                                        logger.debug("Delete all trashed users operation completed in {} seconds",
                                                        duration);
                                });
        }
}
