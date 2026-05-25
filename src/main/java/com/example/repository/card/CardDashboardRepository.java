package com.example.repository.card;

import com.example.entity.card.Card;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CardDashboardRepository implements PanacheRepository<Card> {

    public Uni<Long> getTotalBalance() {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(s.total_balance), 0) " +
                        "FROM saldos s " +
                        "JOIN cards c ON s.card_number = c.card_number " +
                        "WHERE s.deleted_at IS NULL AND c.deleted_at IS NULL",
                Object.class)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTopupAmount() {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(t.topup_amount), 0) " +
                        "FROM topups t " +
                        "JOIN cards c ON t.card_number = c.card_number " +
                        "WHERE t.deleted_at IS NULL AND c.deleted_at IS NULL",
                Object.class)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalWithdrawAmount() {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(s.withdraw_amount), 0) " +
                        "FROM withdraws s " +
                        "JOIN cards c ON s.card_number = c.card_number " +
                        "WHERE s.deleted_at IS NULL AND c.deleted_at IS NULL",
                Object.class)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTransactionAmount() {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(t.amount), 0) " +
                        "FROM transactions t " +
                        "JOIN cards c ON t.card_number = c.card_number " +
                        "WHERE t.deleted_at IS NULL AND c.deleted_at IS NULL",
                Object.class)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTransferAmount() {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(transfer_amount), 0) " +
                        "FROM ( " +
                        "    SELECT transfer_amount FROM transfers WHERE deleted_at IS NULL " +
                        "    UNION ALL " +
                        "    SELECT transfer_amount FROM transfers WHERE deleted_at IS NULL " +
                        ") AS transfer_data",
                Object.class)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }
}
