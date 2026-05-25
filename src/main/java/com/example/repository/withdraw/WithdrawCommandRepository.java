package com.example.repository.withdraw;

import com.example.entity.withdraw.Withdraw;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WithdrawCommandRepository implements PanacheRepository<Withdraw> {

    @WithTransaction
    public Uni<Withdraw> updateStatus(Long withdrawId, String status) {
        return find("withdrawId = ?1 AND deletedAt IS NULL", withdrawId).firstResult()
                .chain(withdraw -> {
                    if (withdraw != null) {
                        try {
                            withdraw.status = com.example.enums.Status.valueOf(status.toUpperCase());
                            withdraw.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                            return persist(withdraw).map(v -> withdraw);
                        } catch (IllegalArgumentException e) {
                            return Uni.createFrom().item(withdraw);
                        }
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Withdraw> trashed(Long withdrawId) {
        return find("withdrawId = ?1 AND deletedAt IS NULL", withdrawId).firstResult()
                .chain(withdraw -> {
                    if (withdraw != null) {
                        withdraw.setDeletedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(withdraw).map(v -> withdraw);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Withdraw> restore(Long withdrawId) {
        return find("withdrawId = ?1 AND deletedAt IS NOT NULL", withdrawId).firstResult()
                .chain(withdraw -> {
                    if (withdraw != null) {
                        withdraw.setDeletedAt(null);
                        return persist(withdraw).map(v -> withdraw);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long withdrawId) {
        return find("withdrawId = ?1", withdrawId).firstResult()
                .chain(withdraw -> {
                    if (withdraw != null) {
                        return delete(withdraw).map(v -> true);
                    }
                    return Uni.createFrom().item(false);
                });
    }

    @WithTransaction
    public Uni<Boolean> restoreAllDeleted() {
        return update("deletedAt = NULL WHERE deletedAt IS NOT NULL")
                .map(count -> count > 0);
    }

    @WithTransaction
    public Uni<Boolean> deleteAllDeleted() {
        return delete("deletedAt IS NOT NULL")
                .map(count -> count > 0);
    }
}
