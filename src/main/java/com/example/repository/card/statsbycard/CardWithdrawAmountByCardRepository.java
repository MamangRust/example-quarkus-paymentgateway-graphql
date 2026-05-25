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
public class CardWithdrawAmountByCardRepository implements PanacheRepository<Card> {

    public Uni<List<CardMonthAmount>> getMonthlyWithdrawAmountByCard(String cardNumber, LocalDate yearDate) {
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
                    CAST(COALESCE(SUM(w.withdraw_amount), 0) AS BIGINT) AS totalWithdrawAmount
                FROM months m
                LEFT JOIN withdraws w
                    ON EXTRACT(MONTH FROM w.withdraw_time) = m.m
                    AND EXTRACT(YEAR FROM w.withdraw_time) = EXTRACT(YEAR FROM CAST(:yearDate AS date))
                    AND w.deleted_at IS NULL
                    AND w.card_number = :cardNumber
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

    public Uni<List<CardYearAmount>> getYearlyWithdrawAmountByCard(String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        EXTRACT(YEAR FROM w.withdraw_time) AS reportYear,
                        COALESCE(SUM(w.withdraw_amount), 0) AS totalAmount
                    FROM withdraws w
                    JOIN cards c ON w.card_number = c.card_number
                    WHERE w.deleted_at IS NULL
                      AND c.deleted_at IS NULL
                      AND w.card_number = :cardNumber
                      AND EXTRACT(YEAR FROM w.withdraw_time) >= :year - 4
                      AND EXTRACT(YEAR FROM w.withdraw_time) <= :year
                    GROUP BY EXTRACT(YEAR FROM w.withdraw_time)
                ), yearly AS (
                    SELECT :year AS reportYear UNION ALL
                    SELECT :year - 1 UNION ALL
                    SELECT :year - 2 UNION ALL
                    SELECT :year - 3 UNION ALL
                    SELECT :year - 4
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(yd.totalAmount, 0) AS BIGINT) AS totalWithdrawAmount
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
