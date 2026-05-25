package com.example.security;

import com.example.entity.User;
import com.example.repository.UserRepository;
import com.example.utils.JwtUtil;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class SecurityContext {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityContext.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    UserRepository userRepository;

    @Inject
    JwtUtil jwtUtil;

    @Context
    HttpHeaders httpHeaders;

    public Uni<Long> getCurrentUserId() {
        if (securityIdentity.isAnonymous()) {
            LOG.debug("Access attempt by anonymous user.");
            return Uni.createFrom().nullItem();
        }

        try {
            String username = securityIdentity.getPrincipal().getName();
            LOG.debug("Username from security identity principal: {}", username);

            if (username != null && !username.isEmpty()) {
                return userRepository.findByUsername(username)
                        .chain(user -> {
                            if (user != null) {
                                LOG.debug("Found user by username '{}', ID: {}", username, user.getUserId());
                                return Uni.createFrom().item(user.getUserId());
                            }
                            return getUserIdFromHeader();
                        });
            }

            return getUserIdFromHeader();
        } catch (Exception e) {
            LOG.error("An error occurred while trying to get the current user ID", e);
            return Uni.createFrom().nullItem();
        }
    }

    private Uni<Long> getUserIdFromHeader() {
        if (httpHeaders != null) {
            String authHeader = httpHeaders.getHeaderString("Authorization");
            LOG.debug("Attempting to parse user ID from Authorization header.");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                Long userId = jwtUtil.getUserIdFromToken(token);
                LOG.debug("User ID parsed from token: {}", userId);

                if (userId != null) {
                    return Uni.createFrom().item(userId);
                }
            }
        }
        return Uni.createFrom().nullItem();
    }

    public Uni<User> getCurrentUser() {
        return getCurrentUserId()
                .chain(userId -> {
                    if (userId != null) {
                        return userRepository.findById(userId);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    public boolean hasRole(String role) {
        return securityIdentity.hasRole(role);
    }

    public Uni<Boolean> hasPermission(String permission) {
        return getCurrentUser()
                .map(currentUser -> {
                    if (currentUser == null) {
                        return false;
                    }

                    return currentUser.getRoles().stream()
                            .anyMatch(role -> hasRolePermission(role.getRoleName(), permission));
                });
    }

    private boolean hasRolePermission(String roleName, String requiredPermission) {
        return switch (roleName) {
            case "ROLE_ADMIN" -> true;
            case "ROLE_USER_MANAGER" ->
                List.of("USER_READ", "USER_WRITE", "USER_DELETE").contains(requiredPermission);
            case "ROLE_MANAGER" -> List.of("ROLE_READ", "ROLE_WRITE").contains(requiredPermission);
            case "ROLE_USER" -> List.of("PROFILE_READ", "PROFILE_WRITE").contains(requiredPermission);
            default -> false;
        };
    }
}