package com.example.repository.withdraw;

import java.util.List;

import com.example.domain.responses.api.PagedResult;
import com.example.entity.withdraw.Withdraw;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WithdrawQueryRepository implements PanacheRepository<Withdraw> {

    public Uni<PagedResult<Withdraw>> findAllWithdraws(String keyword, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND (
                        ?1 IS NULL
                        OR cardNumber LIKE CONCAT('%', ?1, '%')
                        OR CAST(withdrawAmount AS string) LIKE CONCAT('%', ?1, '%')
                        OR CAST(withdrawTime AS string) LIKE CONCAT('%', ?1, '%')
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("withdrawTime"), keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<PagedResult<Withdraw>> findActiveWithdraws(String keyword, int page, int size) {
        return findAllWithdraws(keyword, page, size);
    }

    public Uni<PagedResult<Withdraw>> findTrashedWithdraws(String keyword, int page, int size) {
        var query = """
                    deletedAt IS NOT NULL
                    AND (
                        ?1 IS NULL
                        OR cardNumber LIKE CONCAT('%', ?1, '%')
                        OR CAST(withdrawAmount AS string) LIKE CONCAT('%', ?1, '%')
                        OR CAST(withdrawTime AS string) LIKE CONCAT('%', ?1, '%')
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?1, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("withdrawTime"), keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }

    public Uni<List<Withdraw>> findByCardNumber(String cardNumber) {
        return list("deletedAt IS NULL AND cardNumber = ?1", Sort.descending("withdrawTime"), cardNumber);
    }

    public Uni<PagedResult<Withdraw>> findAllByCardNumber(String cardNumber, String keyword, int page, int size) {
        var query = """
                    deletedAt IS NULL
                    AND cardNumber = ?1
                    AND (
                        ?2 IS NULL
                        OR CAST(withdrawAmount AS string) LIKE CONCAT('%', ?2, '%')
                        OR CAST(withdrawTime AS string) LIKE CONCAT('%', ?2, '%')
                        OR LOWER(CAST(status AS string)) LIKE LOWER(CONCAT('%', ?2, '%'))
                    )
                """;

        var panacheQuery = find(query, Sort.descending("withdrawTime"), cardNumber, keyword)
                .page(page, size);

        return Uni.combine().all().unis(panacheQuery.list(), panacheQuery.count())
                .asTuple()
                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
    }
}
