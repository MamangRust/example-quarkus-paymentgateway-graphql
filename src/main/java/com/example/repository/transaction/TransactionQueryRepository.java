package com.example.repository.transaction;

import java.util.List;

import com.example.domain.responses.api.PagedResult;
import com.example.entity.transaction.Transaction;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransactionQueryRepository implements PanacheRepository<Transaction> {

    public Uni<PagedResult<Transaction>> findTransactions(String search, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(cardNumber) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(paymentMethod) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("transactionTime"), search)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<PagedResult<Transaction>> findActiveTransactions(String search, int page, int size) {
        return findTransactions(search, page, size);
    }

    public Uni<PagedResult<Transaction>> findTrashedTransactions(String search, int page, int size) {
        var query = """
                    deletedAt IS NOT NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(cardNumber) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(paymentMethod) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("transactionTime"), search)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<Transaction> findTransactionById(Long id) {
        return find("transactionId = ?1 AND deletedAt IS NULL", id).firstResult();
    }

    public Uni<PagedResult<Transaction>> findTransactionsByCardNumber(String cardNumber, String filter, int page,
            int size) {
        var query = """
                    deletedAt IS NULL
                    AND cardNumber = ?1
                    AND (
                        ?2 IS NULL
                        OR LOWER(paymentMethod) LIKE LOWER(CONCAT('%', ?2, '%'))
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?2, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("transactionTime"), cardNumber, filter)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<List<Transaction>> findTransactionsByMerchantId(Long merchantId) {
        return list("merchantId = ?1 AND deletedAt IS NULL", Sort.descending("transactionTime"),
                merchantId != null ? merchantId.intValue() : 0);
    }
}
