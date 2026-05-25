package com.example.repository.transaction.statsbycard;

import java.util.List;

import com.example.entity.transaction.Transaction;
import com.example.entity.transaction.TransactionMonthAmount;
import com.example.entity.transaction.TransactionYearAmount;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransactionAmountByCardRepository implements PanacheRepository<Transaction> {

    public Uni<List<TransactionMonthAmount>> findMonthlyAmountsByCard(String cardNumber, Long year) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar' WHEN 4 THEN 'Apr'
                        WHEN 5 THEN 'May' WHEN 6 THEN 'Jun' WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug'
                        WHEN 9 THEN 'Sep' WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transactions t
                    ON EXTRACT(MONTH FROM t.transaction_time) = m.m
                    AND EXTRACT(YEAR FROM t.transaction_time) = :year
                    AND t.card_number = :cardNumber
                    AND t.deleted_at IS NULL
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<TransactionYearAmount>> findYearlyAmountsByCard(String cardNumber, Long endYear) {
        String sql = """
                WITH years AS (
                    SELECT :endYear - 4 AS y UNION ALL
                    SELECT :endYear - 3 AS y UNION ALL
                    SELECT :endYear - 2 AS y UNION ALL
                    SELECT :endYear - 1 AS y UNION ALL
                    SELECT :endYear AS y
                )
                SELECT
                    CAST(y.y AS VARCHAR) AS reportYear,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM years y
                LEFT JOIN transactions t
                    ON EXTRACT(YEAR FROM t.transaction_time) = y.y
                    AND t.card_number = :cardNumber
                    AND t.deleted_at IS NULL
                GROUP BY y.y
                ORDER BY y.y
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("endYear", endYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
