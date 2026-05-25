package com.example.repository.card;

import com.example.entity.card.Card;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CardDashboardByCardRepository implements PanacheRepository<Card> {

    public Uni<Long> getTotalBalanceByCard(String cardNumber) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(s.total_balance), 0) " +
                        "FROM saldos s " +
                        "JOIN cards c ON s.card_number = c.card_number " +
                        "WHERE s.deleted_at IS NULL AND c.deleted_at IS NULL " +
                        "AND c.card_number = :cardNumber",
                Object.class)
                .setParameter("cardNumber", cardNumber)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTopupAmountByCard(String cardNumber) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(t.topup_amount), 0) " +
                        "FROM topups t " +
                        "JOIN cards c ON t.card_number = c.card_number " +
                        "WHERE t.deleted_at IS NULL AND c.deleted_at IS NULL " +
                        "AND c.card_number = :cardNumber",
                Object.class)
                .setParameter("cardNumber", cardNumber)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalWithdrawAmountByCard(String cardNumber) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(s.withdraw_amount), 0) " +
                        "FROM withdraws s " +
                        "JOIN cards c ON s.card_number = c.card_number " +
                        "WHERE s.deleted_at IS NULL AND c.deleted_at IS NULL " +
                        "AND c.card_number = :cardNumber",
                Object.class)
                .setParameter("cardNumber", cardNumber)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTransactionAmountByCard(String cardNumber) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(t.amount), 0) " +
                        "FROM transactions t " +
                        "JOIN cards c ON t.card_number = c.card_number " +
                        "WHERE t.deleted_at IS NULL AND c.deleted_at IS NULL " +
                        "AND c.card_number = :cardNumber",
                Object.class)
                .setParameter("cardNumber", cardNumber)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTransferAmountBySender(String sender) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(transfer_amount), 0) " +
                        "FROM transfers " +
                        "WHERE transfer_from = :sender " +
                        "AND deleted_at IS NULL",
                Object.class)
                .setParameter("sender", sender)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }

    public Uni<Long> getTotalTransferAmountByReceiver(String receiver) {
        return getSession().chain(session -> session.createNativeQuery(
                "SELECT COALESCE(SUM(transfer_amount), 0) " +
                        "FROM transfers " +
                        "WHERE transfer_to = :receiver " +
                        "AND deleted_at IS NULL",
                Object.class)
                .setParameter("receiver", receiver)
                .getSingleResult()
                .map(res -> res == null ? 0L : ((Number) res).longValue()));
    }
}
