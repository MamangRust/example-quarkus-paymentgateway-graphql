package com.example.repository.transaction;

import com.example.entity.transaction.Transaction;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransactionCommandRepository implements PanacheRepository<Transaction> {

    @WithTransaction
    public Uni<Transaction> updateTransactionStatus(Long transactionId, String status) {
        return find("transactionId = ?1 AND deletedAt IS NULL", transactionId).firstResult()
                .chain(transaction -> {
                    if (transaction != null) {
                        try {
                            transaction.status = com.example.enums.Status.valueOf(status.toUpperCase());
                            transaction.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                            return persist(transaction).map(v -> transaction);
                        } catch (IllegalArgumentException e) {
                            return Uni.createFrom().item(transaction);
                        }
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Transaction> trashed(Long transactionId) {
        return find("transactionId = ?1 AND deletedAt IS NULL", transactionId).firstResult()
                .chain(transaction -> {
                    if (transaction != null) {
                        transaction.setDeletedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(transaction).map(v -> transaction);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Transaction> restore(Long transactionId) {
        return find("transactionId = ?1 AND deletedAt IS NOT NULL", transactionId).firstResult()
                .chain(transaction -> {
                    if (transaction != null) {
                        transaction.setDeletedAt(null);
                        return persist(transaction).map(v -> transaction);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long transactionId) {
        return find("transactionId = ?1", transactionId).firstResult()
                .chain(transaction -> {
                    if (transaction != null) {
                        return delete(transaction).map(v -> true);
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
