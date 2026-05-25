package com.example.repository.card;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import com.example.domain.responses.api.PagedResult;
import com.example.entity.card.Card;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CardRepository implements PanacheRepository<Card> {

    public Uni<PagedResult<Card>> findCards(String keyword, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(cardNumber) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardType)   LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardProvider) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<PagedResult<Card>> findActiveCards(String keyword, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(cardNumber) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardType)   LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardProvider) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<PagedResult<Card>> findTrashedCards(String keyword, int page, int size) {
        var query = """
                    deletedAt IS NOT NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(cardNumber) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardType)   LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(cardProvider) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<Card> findCardById(Long cardId) {
        return find("cardId = ?1 AND deletedAt IS NULL", cardId).firstResult();
    }

    public Uni<Card> findCardByUserId(Long userId) {
        if (userId == null) {
            return Uni.createFrom().nullItem();
        }
        return find("userId = ?1 AND deletedAt IS NULL", userId.intValue()).firstResult();
    }

    public Uni<Card> findCardByCardNumber(String cardNumber) {
        return find("cardNumber = ?1 AND deletedAt IS NULL", cardNumber).firstResult();
    }

    @WithTransaction
    public Uni<Card> trashed(Long cardId) {
        return find("cardId = ?1 AND deletedAt IS NULL", cardId).firstResult()
                .chain(card -> {
                    if (card != null) {
                        LocalDateTime date = LocalDateTime.now();
                        card.setDeletedAt(Timestamp.valueOf(date));
                        return persist(card).map(v -> card);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Card> restore(Long cardId) {
        return find("cardId = ?1 AND deletedAt IS NOT NULL", cardId).firstResult()
                .chain(card -> {
                    if (card != null) {
                        card.setDeletedAt(null);
                        return persist(card).map(v -> card);
                    }
                    return Uni.createFrom().nullItem();
                });
    }

    @WithTransaction
    public Uni<Boolean> deletePermanent(Long cardId) {
        return find("cardId = ?1", cardId).firstResult()
                .chain(card -> {
                    if (card != null) {
                        return delete(card).map(v -> true);
                    }
                    return Uni.createFrom().item(false);
                });
    }

    @WithTransaction
    public Uni<Boolean> restoreAllDeleted() {
        return update("deletedAt = NULL WHERE deletedAt IS NOT NULL")
                .map(updatedCount -> updatedCount > 0);
    }

    @WithTransaction
    public Uni<Boolean> deleteAllDeleted() {
        return delete("deletedAt IS NOT NULL")
                .map(deletedCount -> deletedCount > 0);
    }
}
