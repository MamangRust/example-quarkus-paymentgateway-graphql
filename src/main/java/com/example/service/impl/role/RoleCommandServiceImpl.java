package com.example.service.impl.role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.role.CreateRoleRequest;
import com.example.domain.requests.role.UpdateRoleRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.role.RoleResponse;
import com.example.domain.responses.role.RoleResponseDeleteAt;
import com.example.entity.Role;
import com.example.exception.ResourceAlreadyExistsException;
import com.example.repository.RoleRepository;
import com.example.service.role.RoleCommandService;

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
import jakarta.ws.rs.NotFoundException;

@ApplicationScoped
public class RoleCommandServiceImpl implements RoleCommandService {
        private static final Logger logger = LoggerFactory.getLogger(RoleCommandServiceImpl.class);

        RoleRepository roleRepository;
        RedisService redisService;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        @Inject
        public RoleCommandServiceImpl(RoleRepository roleRepository, OpenTelemetry openTelemetry,
                        RedisService redisService) {
                this.roleRepository = roleRepository;
                this.redisService = redisService;
                this.tracer = openTelemetry.getTracer("role-command-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("role-command-service");

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
        public Uni<ApiResponse<RoleResponse>> create(CreateRoleRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("createRole")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "create_role")
                                .setAttribute("role.name", request.getName())
                                .startSpan();

                logger.info("Creating new role with name: {}", request.getName());

                return roleRepository.findByRoleName(request.getName())
                                .chain(existingRole -> {
                                        if (existingRole != null) {
                                                logger.warn("Role creation failed - role already exists: {}",
                                                                request.getName());
                                                span.setStatus(StatusCode.ERROR, "Role already exists");
                                                span.setAttribute("role.create.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "create_role",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"),
                                                                "already_exists"));

                                                throw new RuntimeException("Role with name '" + request.getName()
                                                                + "' already exists");
                                        }

                                        Role newRole = new Role();
                                        newRole.setRoleName(request.getName());
                                        return roleRepository.persist(newRole)
                                                        .map(v -> {
                                                                span.setAttribute("role.id", newRole.getRoleId());
                                                                span.setAttribute("role.create.success", true);

                                                                RoleResponse roleResponse = RoleResponse.from(newRole);

                                                                logger.info("Role created. List caches will be refreshed upon expiry.");
                                                                logger.info("Successfully created role with id: {} and name: {}",
                                                                                newRole.getRoleId(),
                                                                                newRole.getRoleName());
                                                                span.setStatus(StatusCode.OK);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "create_role",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success("Role created successfully",
                                                                                roleResponse);
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error creating role with name: {}", request.getName(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_role",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_role"));
                                        logger.debug("Create role operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<RoleResponse>> update(UpdateRoleRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("updateRole")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "update_role")
                                .setAttribute("role.id", request.getRoleId().toString())
                                .setAttribute("role.new_name", request.getName())
                                .startSpan();

                logger.info("Updating role with id: {} to new name: {}", request.getRoleId(), request.getName());

                return roleRepository.findById(request.getRoleId().longValue())
                                .chain(existingRole -> {
                                        if (existingRole == null) {
                                                logger.warn("Role update failed - role not found with id: {}",
                                                                request.getRoleId());
                                                span.setStatus(StatusCode.ERROR, "Role not found");
                                                span.setAttribute("role.update.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "update_role",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new NotFoundException(
                                                                "Role not found with id: " + request.getRoleId());
                                        }

                                        span.setAttribute("role.old_name", existingRole.getRoleName());

                                        Uni<Role> updateFlow;
                                        if (!existingRole.getRoleName().equals(request.getName())) {
                                                updateFlow = roleRepository.findByRoleName(request.getName())
                                                                .chain(duplicateRole -> {
                                                                        if (duplicateRole != null) {
                                                                                logger.warn("Role update failed - new name '{}' already exists for another role",
                                                                                                request.getName());
                                                                                span.setStatus(StatusCode.ERROR,
                                                                                                "Role name already exists");
                                                                                span.setAttribute("role.update.success",
                                                                                                false);

                                                                                requestsTotal.add(1, Attributes.of(
                                                                                                AttributeKey.stringKey(
                                                                                                                "operation"),
                                                                                                "update_role",
                                                                                                AttributeKey.stringKey(
                                                                                                                "status"),
                                                                                                "failed",
                                                                                                AttributeKey.stringKey(
                                                                                                                "error_type"),
                                                                                                "already_exists"));

                                                                                throw new ResourceAlreadyExistsException(
                                                                                                "Role with name '"
                                                                                                                + request.getName()
                                                                                                                + "' already exists");
                                                                        }
                                                                        existingRole.setRoleName(request.getName());
                                                                        return roleRepository.persist(existingRole)
                                                                                        .map(v -> existingRole);
                                                                });
                                        } else {
                                                updateFlow = Uni.createFrom().item(existingRole);
                                        }

                                        return updateFlow.chain(updatedRole -> {
                                                RoleResponse roleResponse = RoleResponse.from(updatedRole);
                                                String cacheKey = "role:" + request.getRoleId();

                                                return redisService.deleteReactive(cacheKey)
                                                                .map(v -> {
                                                                        logger.info("Invalidated cache for key: {}",
                                                                                        cacheKey);
                                                                        logger.info("Successfully updated role with id: {}",
                                                                                        request.getRoleId());
                                                                        span.setStatus(StatusCode.OK);
                                                                        span.setAttribute("role.update.success", true);

                                                                        requestsTotal.add(1, Attributes.of(
                                                                                        AttributeKey.stringKey(
                                                                                                        "operation"),
                                                                                        "update_role",
                                                                                        AttributeKey.stringKey(
                                                                                                        "status"),
                                                                                        "success"));

                                                                        return ApiResponse.success(
                                                                                        "Role updated successfully",
                                                                                        roleResponse);
                                                                });
                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error updating role with id: {}", request.getRoleId(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_role",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_role"));
                                        logger.debug("Update role operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<RoleResponseDeleteAt>> trash(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("trashRole")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "trash_role")
                                .setAttribute("role.id", id.toString())
                                .startSpan();

                logger.info("Trashing role with id: {}", id);

                return roleRepository.trash(id)
                                .chain(trashedRole -> {
                                        if (trashedRole == null) {
                                                logger.warn("Role trash failed - role not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "Role not found");
                                                span.setAttribute("role.trash.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "trash_role",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new NotFoundException("Role not found with id: " + id);
                                        }

                                        span.setAttribute("role.name", trashedRole.getRoleName());
                                        span.setAttribute("role.trash.success", true);

                                        RoleResponseDeleteAt roleResponseDeleteAt = RoleResponseDeleteAt
                                                        .from(trashedRole);
                                        String cacheKey = "role:" + id;

                                        return redisService.deleteReactive(cacheKey)
                                                        .map(v -> {
                                                                logger.info("Invalidated cache for key: {}", cacheKey);
                                                                logger.info("Successfully trashed role with id: {}",
                                                                                id);
                                                                span.setStatus(StatusCode.OK);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "trash_role",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success("Role trashed successfully",
                                                                                roleResponseDeleteAt);
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error trashing role with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_role",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_role"));
                                        logger.debug("Trash role operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<RoleResponseDeleteAt>> restore(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreRole")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "restore_role")
                                .setAttribute("role.id", id.toString())
                                .startSpan();

                logger.info("Restoring role with id: {}", id);

                return roleRepository.restore(id)
                                .chain(restoredRole -> {
                                        if (restoredRole == null) {
                                                logger.warn("Role restore failed - role not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "Role not found");
                                                span.setAttribute("role.restore.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "restore_role",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new NotFoundException("Restored role not found with id: " + id);
                                        }

                                        span.setAttribute("role.name", restoredRole.getRoleName());
                                        span.setAttribute("role.restore.success", true);

                                        RoleResponseDeleteAt roleResponseDeleteAt = RoleResponseDeleteAt
                                                        .from(restoredRole);
                                        String cacheKey = "role:" + id;

                                        return redisService.deleteReactive(cacheKey)
                                                        .map(v -> {
                                                                logger.info("Invalidated cache for key: {}", cacheKey);
                                                                logger.info("Successfully restored role with id: {}",
                                                                                id);
                                                                span.setStatus(StatusCode.OK);

                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "restore_role",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success(
                                                                                "Role restored successfully",
                                                                                roleResponseDeleteAt);
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error restoring role with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_role",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_role"));
                                        logger.debug("Restore role operation completed in {} seconds", duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> deletePermanent(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteRolePermanent")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "delete_role_permanent")
                                .setAttribute("role.id", id.toString())
                                .startSpan();

                logger.info("Permanently deleting role with id: {}", id);

                return roleRepository.findById(id)
                                .chain(roleToDelete -> {
                                        if (roleToDelete == null) {
                                                logger.warn("Permanent delete failed - role not found with id: {}", id);
                                                span.setStatus(StatusCode.ERROR, "Role not found");
                                                span.setAttribute("role.delete.success", false);

                                                requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"),
                                                                "delete_role_permanent",
                                                                AttributeKey.stringKey("status"), "failed",
                                                                AttributeKey.stringKey("error_type"), "not_found"));

                                                throw new NotFoundException("Role not found with id: " + id);
                                        }

                                        span.setAttribute("role.name", roleToDelete.getRoleName());

                                        return roleRepository.deletePermanent(id)
                                                        .chain(v -> {
                                                                String cacheKey = "role:" + id;
                                                                return redisService.deleteReactive(cacheKey)
                                                                                .map(v2 -> {
                                                                                        logger.info("Invalidated cache for key: {}",
                                                                                                        cacheKey);
                                                                                        logger.info("Successfully permanently deleted role with id: {}",
                                                                                                        id);
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        span.setAttribute(
                                                                                                        "role.delete.success",
                                                                                                        true);

                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "delete_role_permanent",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Role deleted permanently");
                                                                                });
                                                        });
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error permanently deleting role with id: {}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_role_permanent",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_role_permanent"));
                                        logger.debug("Permanent delete role operation completed in {} seconds",
                                                        duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> restoreAllTrashedRoles() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreAllTrashedRoles")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "restore_all_trashed_roles")
                                .startSpan();

                logger.info("Restoring all trashed roles");

                return roleRepository.restoreAllDeleted()
                                .map(v -> {
                                        logger.warn("All trashed roles restored. Caches will be refreshed upon expiry or next access.");
                                        logger.info("Successfully restored all trashed roles");
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_roles",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse
                                                        .success("All trashed roles have been restored successfully");
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error restoring all trashed roles", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_roles",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "restore_all_trashed_roles"));
                                        logger.debug("Restore all trashed roles operation completed in {} seconds",
                                                        duration);
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Void>> deleteAllTrashedRoles() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteAllTrashedRoles")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "role-service")
                                .setAttribute("operation", "delete_all_trashed_roles")
                                .startSpan();

                logger.info("Permanently deleting all trashed roles");

                return roleRepository.deleteAllDeleted()
                                .map(v -> {
                                        logger.warn("All trashed roles deleted. Caches will be refreshed upon expiry or next access.");
                                        logger.info("Successfully deleted all trashed roles");
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_trashed_roles",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success("All trashed roles have been deleted permanently");
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("Error deleting all trashed roles", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_trashed_roles",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"),
                                                        "delete_all_trashed_roles"));
                                        logger.debug("Delete all trashed roles operation completed in {} seconds",
                                                        duration);
                                });
        }
}
