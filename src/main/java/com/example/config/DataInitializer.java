package com.example.config;

import com.example.entity.Role;
import com.example.repository.RoleRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DataInitializer {

    private static final Logger LOG = Logger.getLogger(DataInitializer.class);

    @Inject
    RoleRepository roleRepository;

    @Inject
    Vertx vertx;

    public void onStart(@Observes StartupEvent event) {
        LOG.info("Initializing default roles...");

        ContextInternal rootContext = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal duplicated = rootContext.duplicate();
        VertxContextSafetyToggle.setContextSafe(duplicated, true);

        duplicated.runOnContext(v -> initRoles().subscribe().with(
                success -> LOG.info("Roles initialized successfully"),
                failure -> LOG.warn("Role initialization failed: " + failure.getMessage())));
    }

    @WithTransaction
    public Uni<Void> initRoles() {
        return createRoleIfNotExists("ROLE_USER")
                .flatMap(v -> createRoleIfNotExists("ROLE_ADMIN"));
    }

    private Uni<Void> createRoleIfNotExists(String roleName) {
        return roleRepository.findByRoleName(roleName)
                .flatMap(existing -> {
                    if (existing != null) {
                        LOG.info("Role already exists: " + roleName);
                        return Uni.createFrom().voidItem();
                    }
                    Role role = new Role();
                    role.setRoleName(roleName);
                    return roleRepository.persist(role).replaceWithVoid();
                });
    }
}