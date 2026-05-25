package com.example.repository.merchant;

import java.util.List;

import com.example.domain.responses.api.PagedResult;
import com.example.entity.merchant.Merchant;
import com.example.entity.merchant.MerchantTransactions;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MerchantTransactionRepository implements PanacheRepository<Merchant> {

        public Uni<PagedResult<MerchantTransactions>> findAllTransactions(String search, int page, int size) {
                String baseSql = """
                                FROM transactions t
                                JOIN merchants m ON t.merchant_id = m.merchant_id
                                WHERE t.deleted_at IS NULL
                                  AND (:search IS NULL OR t.card_number ILIKE CONCAT('%', :search, '%') OR t.payment_method ILIKE CONCAT('%', :search, '%'))
                                """;

                String selectSql = "SELECT t.transaction_id, t.card_number, t.amount, t.payment_method, t.merchant_id, m.name, t.transaction_time, t.created_at, t.updated_at, t.deleted_at "
                                + baseSql + " ORDER BY t.transaction_time DESC";
                String countSql = "SELECT COUNT(*) " + baseSql;

                int offset = page * size;

                Uni<List<MerchantTransactions>> listUni = getSession()
                                .chain(session -> session.createNativeQuery(selectSql, Object[].class)
                                                .setParameter("search", search)
                                                .setFirstResult(offset)
                                                .setMaxResults(size)
                                                .getResultList()
                                                .map(list -> list.stream()
                                                                .map(row -> new MerchantTransactions(
                                                                                row[0] != null ? ((Number) row[0])
                                                                                                .intValue() : null,
                                                                                (String) row[1],
                                                                                row[2] != null ? ((Number) row[2])
                                                                                                .intValue() : null,
                                                                                (String) row[3],
                                                                                row[4] != null ? ((Number) row[4])
                                                                                                .intValue() : null,
                                                                                (String) row[5],
                                                                                (java.sql.Timestamp) row[6],
                                                                                (java.sql.Timestamp) row[7],
                                                                                (java.sql.Timestamp) row[8],
                                                                                (java.sql.Timestamp) row[9]))
                                                                .toList()));

                Uni<Long> countUni = getSession().chain(session -> session.createNativeQuery(countSql, Object.class)
                                .setParameter("search", search)
                                .getSingleResult()
                                .map(res -> res == null ? 0L : ((Number) res).longValue()));

                return Uni.combine().all().unis(listUni, countUni)
                                .asTuple()
                                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
        }

        public Uni<PagedResult<MerchantTransactions>> findAllTransactionsByMerchant(Long merchantId, String search,
                        int page, int size) {
                String baseSql = """
                                FROM transactions t
                                JOIN merchants m ON t.merchant_id = m.merchant_id
                                WHERE t.deleted_at IS NULL
                                  AND t.merchant_id = :merchantId
                                  AND (:search IS NULL OR t.card_number ILIKE CONCAT('%', :search, '%') OR t.payment_method ILIKE CONCAT('%', :search, '%'))
                                """;

                String selectSql = "SELECT t.transaction_id, t.card_number, t.amount, t.payment_method, t.merchant_id, m.name, t.transaction_time, t.created_at, t.updated_at, t.deleted_at "
                                + baseSql + " ORDER BY t.transaction_time DESC";
                String countSql = "SELECT COUNT(*) " + baseSql;

                int offset = page * size;

                Uni<List<MerchantTransactions>> listUni = getSession()
                                .chain(session -> session.createNativeQuery(selectSql, Object[].class)
                                                .setParameter("merchantId", merchantId)
                                                .setParameter("search", search)
                                                .setFirstResult(offset)
                                                .setMaxResults(size)
                                                .getResultList()
                                                .map(list -> list.stream()
                                                                .map(row -> new MerchantTransactions(
                                                                                row[0] != null ? ((Number) row[0])
                                                                                                .intValue() : null,
                                                                                (String) row[1],
                                                                                row[2] != null ? ((Number) row[2])
                                                                                                .intValue() : null,
                                                                                (String) row[3],
                                                                                row[4] != null ? ((Number) row[4])
                                                                                                .intValue() : null,
                                                                                (String) row[5],
                                                                                (java.sql.Timestamp) row[6],
                                                                                (java.sql.Timestamp) row[7],
                                                                                (java.sql.Timestamp) row[8],
                                                                                (java.sql.Timestamp) row[9]))
                                                                .toList()));

                Uni<Long> countUni = getSession().chain(session -> session.createNativeQuery(countSql, Object.class)
                                .setParameter("merchantId", merchantId)
                                .setParameter("search", search)
                                .getSingleResult()
                                .map(res -> res == null ? 0L : ((Number) res).longValue()));

                return Uni.combine().all().unis(listUni, countUni)
                                .asTuple()
                                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
        }

        public Uni<PagedResult<MerchantTransactions>> findAllTransactionsByApiKey(String apiKey, String search,
                        int page,
                        int size) {
                String baseSql = """
                                FROM transactions t
                                JOIN merchants m ON t.merchant_id = m.merchant_id
                                WHERE t.deleted_at IS NULL
                                  AND m.api_key = :apiKey
                                  AND (:search IS NULL OR t.card_number ILIKE CONCAT('%', :search, '%') OR t.payment_method ILIKE CONCAT('%', :search, '%'))
                                """;

                String selectSql = "SELECT t.transaction_id, t.card_number, t.amount, t.payment_method, t.merchant_id, m.name, t.transaction_time, t.created_at, t.updated_at, t.deleted_at "
                                + baseSql + " ORDER BY t.transaction_time DESC";
                String countSql = "SELECT COUNT(*) " + baseSql;

                int offset = page * size;

                Uni<List<MerchantTransactions>> listUni = getSession()
                                .chain(session -> session.createNativeQuery(selectSql, Object[].class)
                                                .setParameter("apiKey", apiKey)
                                                .setParameter("search", search)
                                                .setFirstResult(offset)
                                                .setMaxResults(size)
                                                .getResultList()
                                                .map(list -> list.stream()
                                                                .map(row -> new MerchantTransactions(
                                                                                row[0] != null ? ((Number) row[0])
                                                                                                .intValue() : null,
                                                                                (String) row[1],
                                                                                row[2] != null ? ((Number) row[2])
                                                                                                .intValue() : null,
                                                                                (String) row[3],
                                                                                row[4] != null ? ((Number) row[4])
                                                                                                .intValue() : null,
                                                                                (String) row[5],
                                                                                (java.sql.Timestamp) row[6],
                                                                                (java.sql.Timestamp) row[7],
                                                                                (java.sql.Timestamp) row[8],
                                                                                (java.sql.Timestamp) row[9]))
                                                                .toList()));

                Uni<Long> countUni = getSession().chain(session -> session.createNativeQuery(countSql, Object.class)
                                .setParameter("apiKey", apiKey)
                                .setParameter("search", search)
                                .getSingleResult()
                                .map(res -> res == null ? 0L : ((Number) res).longValue()));

                return Uni.combine().all().unis(listUni, countUni)
                                .asTuple()
                                .map(tuple -> new PagedResult<>(tuple.getItem1(), tuple.getItem2().intValue()));
        }
}
