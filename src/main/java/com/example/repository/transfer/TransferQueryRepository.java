package com.example.repository.transfer;

import java.util.List;

import com.example.domain.responses.api.PagedResult;
import com.example.entity.transfer.Transfer;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransferQueryRepository implements PanacheRepository<Transfer> {

    public Uni<PagedResult<Transfer>> findTransfers(String search, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(transferFrom) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(transferTo) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("transferTime"), search)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<PagedResult<Transfer>> findActiveTransfers(String search, int page, int size) {
        return findTransfers(search, page, size);
    }

    public Uni<PagedResult<Transfer>> findTrashedTransfers(String search, int page, int size) {
        var query = """
                    deletedAt IS NOT NULL
                    AND (
                        ?1 IS NULL
                        OR LOWER(transferFrom) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(transferTo) LIKE LOWER(CONCAT('%', ?1, '%'))
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("transferTime"), search)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<Transfer> findTransferById(Long id) {
        return find("transferId = ?1 AND deletedAt IS NULL", id).firstResult();
    }

    public Uni<List<Transfer>> findTransfersByCardNumber(String cardNumber) {
        return list("deletedAt IS NULL AND (transferFrom = ?1 OR transferTo = ?1)", Sort.descending("transferTime"),
                cardNumber);
    }

    public Uni<List<Transfer>> findTransfersBySourceCard(String cardNumber) {
        return list("deletedAt IS NULL AND transferFrom = ?1", Sort.descending("transferTime"), cardNumber);
    }

    public Uni<List<Transfer>> findTransfersByDestinationCard(String cardNumber) {
        return list("deletedAt IS NULL AND transferTo = ?1", Sort.descending("transferTime"), cardNumber);
    }
}
