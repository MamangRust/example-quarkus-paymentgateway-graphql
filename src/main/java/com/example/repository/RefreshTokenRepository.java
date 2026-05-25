package com.example.repository;

import com.example.entity.RefreshToken;
import com.example.entity.User;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@ApplicationScoped
public class RefreshTokenRepository implements PanacheRepository<RefreshToken> {
    @Inject
    UserRepository userRepository;

    public Uni<RefreshToken> findByToken(String token) {
        return find("token", token).firstResult();
    }

    public Uni<RefreshToken> findByUserId(Long userId) {
        return find("user.id", userId).firstResult();
    }

    @WithTransaction
    public Uni<RefreshToken> create(User user, String token, LocalDateTime expiration) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(token);
        refreshToken.setExpiration(Timestamp.valueOf(expiration));

        return persist(refreshToken).map(v -> refreshToken);
    }

    @WithTransaction
    public Uni<RefreshToken> updateByUserId(Long userId, String newToken, LocalDateTime newExpiration) {
        return findByUserId(userId)
                .chain(existingToken -> {
                    if (existingToken != null) {
                        existingToken.setToken(newToken);
                        existingToken.setExpiration(Timestamp.valueOf(newExpiration));
                        return persist(existingToken).map(v -> existingToken);
                    } else {
                        return userRepository.findById(userId)
                                .chain(user -> {
                                    RefreshToken newRefreshToken = new RefreshToken();
                                    newRefreshToken.setUser(user);
                                    newRefreshToken.setToken(newToken);
                                    newRefreshToken.setExpiration(Timestamp.valueOf(newExpiration));
                                    return persist(newRefreshToken).map(v -> newRefreshToken);
                                });
                    }
                });
    }

    @WithTransaction
    public Uni<Boolean> deleteByToken(String token) {
        return delete("token", token).map(count -> count > 0);
    }

    @WithTransaction
    public Uni<Boolean> deleteByUserId(Long userId) {
        return delete("user.id", userId).map(count -> count > 0);
    }

    @WithTransaction
    public Uni<Boolean> deleteExpiredTokens() {
        return delete("expiration < ?1", Timestamp.valueOf(LocalDateTime.now())).map(count -> count > 0);
    }
}