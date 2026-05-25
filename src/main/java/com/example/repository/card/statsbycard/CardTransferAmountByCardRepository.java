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
public class CardTransferAmountByCardRepository implements PanacheRepository<Card> {

    public Uni<List<CardMonthAmount>> getMonthlyTransferAmountBySender(String sender, LocalDate yearDate) {
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
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM
                    months m
                LEFT JOIN
                    transfers t ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = EXTRACT(YEAR FROM :yearDate)
                    AND t.deleted_at IS NULL
                    AND t.transfer_from = :sender
                GROUP BY
                    m.m
                ORDER BY
                    m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("yearDate", yearDate)
                .setParameter("sender", sender)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<CardMonthAmount>> getMonthlyTransferAmountByReceiver(String receiver, LocalDate yearDate) {
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
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM
                    months m
                LEFT JOIN
                    transfers t ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = EXTRACT(YEAR FROM :yearDate)
                    AND t.deleted_at IS NULL
                    AND t.transfer_to = :receiver
                GROUP BY
                    m.m
                ORDER BY
                    m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("yearDate", yearDate)
                .setParameter("receiver", receiver)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<CardYearAmount>> getYearlyTransferAmountBySender(String sender, Long year) {
        String sql = """
                WITH years AS (
                    SELECT :year - 4 AS y UNION ALL
                    SELECT :year - 3 AS y UNION ALL
                    SELECT :year - 2 AS y UNION ALL
                    SELECT :year - 1 AS y UNION ALL
                    SELECT :year AS y
                )
                SELECT
                    CAST(y.y AS VARCHAR) AS reportYear,
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM
                    years y
                LEFT JOIN
                    transfers t ON EXTRACT(YEAR FROM t.transfer_time) = y.y
                      AND t.deleted_at IS NULL
                      AND t.transfer_from = :sender
                GROUP BY
                    y.y
                ORDER BY
                    y.y
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("year", year)
                .setParameter("sender", sender)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<CardYearAmount>> getYearlyTransferAmountByReceiver(String receiver, Long year) {
        String sql = """
                WITH years AS (
                    SELECT :year - 4 AS y UNION ALL
                    SELECT :year - 3 AS y UNION ALL
                    SELECT :year - 2 AS y UNION ALL
                    SELECT :year - 1 AS y UNION ALL
                    SELECT :year AS y
                )
                SELECT
                    CAST(y.y AS VARCHAR) AS reportYear,
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM
                    years y
                LEFT JOIN
                    transfers t ON EXTRACT(YEAR FROM t.transfer_time) = y.y
                      AND t.deleted_at IS NULL
                      AND t.transfer_to = :receiver
                GROUP BY
                    y.y
                ORDER BY
                    y.y
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("year", year)
                .setParameter("receiver", receiver)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new CardYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
