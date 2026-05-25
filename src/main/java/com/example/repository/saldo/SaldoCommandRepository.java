package com.example.repository.saldo;

import com.example.entity.saldo.Saldo;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SaldoCommandRepository implements PanacheRepository<Saldo> {

    @WithTransaction
    public Uni<Saldo> trashed(Long saldoId) {
        return find("saldoId = ?1 AND deletedAt IS NULL", saldoId).firstResult()
                .chain(saldo -> {
                    if (saldo != null) {
                        saldo.setDeletedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                        return persist(saldo).map(v -> saldo);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Saldo> restore(Long saldoId) {
        return find("saldoId = ?1 AND deletedAt IS NOT NULL", saldoId).firstResult()
                .chain(saldo -> {
                    if (saldo != null) {
                        saldo.setDeletedAt(null);
                        return persist(saldo).map(v -> saldo);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Integer> updateBalanceByCardNumber(String cardNumber, Long newBalance) {
        return update("totalBalance = ?1, updatedAt = ?2 WHERE cardNumber = ?3 AND deletedAt IS NULL",
                newBalance != null ? newBalance.intValue() : 0,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                cardNumber);
    }

    @WithTransaction
    public Uni<Integer> updateWithdrawByCardNumber(String cardNumber, Long withdrawAmount) {
        java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
        return update(
                "withdrawAmount = ?1, withdrawTime = ?2, updatedAt = ?3 WHERE cardNumber = ?4 AND deletedAt IS NULL",
                withdrawAmount != null ? withdrawAmount.intValue() : 0,
                now,
                now,
                cardNumber);
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long saldoId) {
        return find("saldoId = ?1", saldoId).firstResult()
                .chain(saldo -> {
                    if (saldo != null) {
                        return delete(saldo).map(v -> true);
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
