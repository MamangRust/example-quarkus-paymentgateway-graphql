package com.example.repository.card.statsbycard;

import java.time.LocalDate;
import java.util.List;

import com.example.entity.card.Card;
import com.example.entity.card.CardMonthAmount;
import com.example.entity.card.CardYearAmount;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CardTransactionAmountByCardRepository implements PanacheRepository<Card> {

    public Uni<List<CardMonthAmount>> getMonthlyTransactionAmountByCard(String cardNumber, LocalDate yearDate) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                        WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                        WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                        WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transactions t
                    ON EXTRACT(MONTH FROM t.transaction_time) = m.m
                    AND EXTRACT(YEAR FROM t.transaction_time) = EXTRACT(YEAR FROM CAST(:yearDate AS date))
                    AND t.deleted_at IS NULL
                    AND t.card_number = :cardNumber
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("yearDate", yearDate)
                .setParameter("cardNumber", cardNumber)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<CardYearAmount>> getYearlyTransactionAmountByCard(String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        EXTRACT(YEAR FROM t.transaction_time) AS reportYear,
                        COALESCE(SUM(t.amount), 0) AS totalAmount
                    FROM transactions t
                    JOIN cards c ON t.card_number = c.card_number
                    WHERE t.deleted_at IS NULL
                      AND c.deleted_at IS NULL
                      AND t.card_number = :cardNumber
                      AND EXTRACT(YEAR FROM t.transaction_time) >= :year - 4
                      AND EXTRACT(YEAR FROM t.transaction_time) <= :year
                    GROUP BY EXTRACT(YEAR FROM t.transaction_time)
                ), yearly AS (
                    SELECT :year AS reportYear UNION ALL
                    SELECT :year - 1 UNION ALL
                    SELECT :year - 2 UNION ALL
                    SELECT :year - 3 UNION ALL
                    SELECT :year - 4
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(yd.totalAmount, 0) AS BIGINT) AS totalAmount
                FROM yearly y
                LEFT JOIN yearly_data yd ON y.reportYear = yd.reportYear
                ORDER BY y.reportYear
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("year", year)
                .setParameter("cardNumber", cardNumber)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
