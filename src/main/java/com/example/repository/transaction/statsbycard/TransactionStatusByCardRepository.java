package com.example.repository.transaction.statsbycard;

import java.util.List;

import com.example.entity.transaction.Transaction;
import com.example.entity.transaction.TransactionMonthStatusFailed;
import com.example.entity.transaction.TransactionMonthStatusSuccess;
import com.example.entity.transaction.TransactionYearStatusFailed;
import com.example.entity.transaction.TransactionYearStatusSuccess;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransactionStatusByCardRepository implements PanacheRepository<Transaction> {

    public Uni<List<TransactionMonthStatusSuccess>> findMonthTransactionStatusSuccessByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS mon UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS VARCHAR) AS reportYear,
                    CASE m.mon
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar' WHEN 4 THEN 'Apr'
                        WHEN 5 THEN 'May' WHEN 6 THEN 'Jun' WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug'
                        WHEN 9 THEN 'Sep' WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalSuccess,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transactions t
                    ON EXTRACT(MONTH FROM t.transaction_time) = m.mon
                    AND EXTRACT(YEAR FROM t.transaction_time) = :startYear
                    AND t.card_number = :cardNumber
                    AND t.deleted_at IS NULL
                GROUP BY m.mon
                ORDER BY m.mon
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionMonthStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TransactionMonthStatusFailed>> findMonthTransactionStatusFailedByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS mon UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS VARCHAR) AS reportYear,
                    CASE m.mon
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar' WHEN 4 THEN 'Apr'
                        WHEN 5 THEN 'May' WHEN 6 THEN 'Jun' WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug'
                        WHEN 9 THEN 'Sep' WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalFailed,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'FAILED' THEN t.amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transactions t
                    ON EXTRACT(MONTH FROM t.transaction_time) = m.mon
                    AND EXTRACT(YEAR FROM t.transaction_time) = :startYear
                    AND t.card_number = :cardNumber
                    AND t.deleted_at IS NULL
                GROUP BY m.mon
                ORDER BY m.mon
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionMonthStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TransactionYearStatusSuccess>> findYearlyTransactionStatusSuccessByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transaction_time) AS VARCHAR) AS reportYear,
                        CAST(COUNT(*) AS BIGINT) AS totalSuccess,
                        CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                    FROM transactions t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'SUCCESS'
                      AND t.card_number = :cardNumber
                      AND (EXTRACT(YEAR FROM t.transaction_time) = :year OR EXTRACT(YEAR FROM t.transaction_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.transaction_time)
                ), formatted_data AS (
                    SELECT reportYear, totalSuccess, totalAmount FROM yearly_data
                    UNION ALL
                    SELECT CAST(:year AS VARCHAR), 0, 0
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year AS VARCHAR))
                    UNION ALL
                    SELECT CAST(:year - 1 AS VARCHAR), 0, 0
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year - 1 AS VARCHAR))
                )
                SELECT * FROM formatted_data
                ORDER BY reportYear DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionYearStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<TransactionYearStatusFailed>> findYearlyTransactionStatusFailedByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transaction_time) AS VARCHAR) AS reportYear,
                        CAST(COUNT(*) AS BIGINT) AS totalFailed,
                        CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                    FROM transactions t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'FAILED'
                      AND t.card_number = :cardNumber
                      AND (EXTRACT(YEAR FROM t.transaction_time) = :year OR EXTRACT(YEAR FROM t.transaction_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.transaction_time)
                ), formatted_data AS (
                    SELECT reportYear, totalFailed, totalAmount FROM yearly_data
                    UNION ALL
                    SELECT CAST(:year AS VARCHAR), 0, 0
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year AS VARCHAR))
                    UNION ALL
                    SELECT CAST(:year - 1 AS VARCHAR), 0, 0
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year - 1 AS VARCHAR))
                )
                SELECT * FROM formatted_data
                ORDER BY reportYear DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransactionYearStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }
}
