package com.example.repository.transfer;

import com.example.entity.transfer.Transfer;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransferCommandRepository implements PanacheRepository<Transfer> {

    @WithTransaction
    public Uni<Transfer> updateTransferAmount(Long transferId, Long amount) {
        return find("transferId = ?1 AND deletedAt IS NULL", transferId).firstResult()
                .chain(transfer -> {
                    if (transfer != null) {
                        transfer.transferAmount = amount != null ? amount.intValue() : 0;
                        transfer.transferTime = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
                        transfer.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(transfer).map(v -> transfer);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Transfer> updateTransferStatus(Long transferId, String status) {
        return find("transferId = ?1 AND deletedAt IS NULL", transferId).firstResult()
                .chain(transfer -> {
                    if (transfer != null) {
                        try {
                            transfer.status = com.example.enums.Status.valueOf(status.toUpperCase());
                            transfer.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                            return persist(transfer).map(v -> transfer);
                        } catch (IllegalArgumentException e) {
                            return Uni.createFrom().item(transfer);
                        }
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Transfer> trashed(Long transferId) {
        return find("transferId = ?1 AND deletedAt IS NULL", transferId).firstResult()
                .chain(transfer -> {
                    if (transfer != null) {
                        transfer.setDeletedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(transfer).map(v -> transfer);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Transfer> restore(Long transferId) {
        return find("transferId = ?1 AND deletedAt IS NOT NULL", transferId).firstResult()
                .chain(transfer -> {
                    if (transfer != null) {
                        transfer.setDeletedAt(null);
                        return persist(transfer).map(v -> transfer);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long transferId) {
        return find("transferId = ?1", transferId).firstResult()
                .chain(transfer -> {
                    if (transfer != null) {
                        return delete(transfer).map(v -> true);
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
