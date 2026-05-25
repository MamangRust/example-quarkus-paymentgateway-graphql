package com.example.repository.topup;

import com.example.entity.topup.Topup;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TopupCommandRepository implements PanacheRepository<Topup> {

    @WithTransaction
    public Uni<Topup> updateTopupAmount(Long topupId, Long amount) {
        return find("topupId = ?1 AND deletedAt IS NULL", topupId).firstResult()
                .chain(topup -> {
                    if (topup != null) {
                        topup.topupAmount = amount != null ? amount.intValue() : 0;
                        topup.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(topup).map(v -> topup);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Topup> updateTopupStatus(Long topupId, String status) {
        return find("topupId = ?1 AND deletedAt IS NULL", topupId).firstResult()
                .chain(topup -> {
                    if (topup != null) {
                        try {
                            topup.status = com.example.enums.Status.valueOf(status.toUpperCase());
                            topup.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                            return persist(topup).map(v -> topup);
                        } catch (IllegalArgumentException e) {
                            return Uni.createFrom().item(topup);
                        }
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Topup> trashed(Long topupId) {
        return find("topupId = ?1 AND deletedAt IS NULL", topupId).firstResult()
                .chain(topup -> {
                    if (topup != null) {
                        topup.setDeletedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(topup).map(v -> topup);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Topup> restore(Long topupId) {
        return find("topupId = ?1 AND deletedAt IS NOT NULL", topupId).firstResult()
                .chain(topup -> {
                    if (topup != null) {
                        topup.setDeletedAt(null);
                        return persist(topup).map(v -> topup);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long topupId) {
        return find("topupId = ?1", topupId).firstResult()
                .chain(topup -> {
                    if (topup != null) {
                        return delete(topup).map(v -> true);
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
