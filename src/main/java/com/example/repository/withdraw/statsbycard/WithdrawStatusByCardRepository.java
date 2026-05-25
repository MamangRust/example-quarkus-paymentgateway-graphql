package com.example.repository.withdraw.statsbycard;

import java.util.List;

import com.example.entity.withdraw.Withdraw;
import com.example.entity.withdraw.WithdrawMonthStatusFailed;
import com.example.entity.withdraw.WithdrawMonthStatusSuccess;
import com.example.entity.withdraw.WithdrawYearStatusFailed;
import com.example.entity.withdraw.WithdrawYearStatusSuccess;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WithdrawStatusByCardRepository implements PanacheRepository<Withdraw> {

    public Uni<List<WithdrawMonthStatusSuccess>> findMonthWithdrawStatusSuccessByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS VARCHAR) AS reportYear,
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar' WHEN 4 THEN 'Apr'
                        WHEN 5 THEN 'May' WHEN 6 THEN 'Jun' WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug'
                        WHEN 9 THEN 'Sep' WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN w.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalSuccess,
                    CAST(COALESCE(SUM(CASE WHEN w.status = 'SUCCESS' THEN w.withdraw_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN withdraws w
                    ON EXTRACT(MONTH FROM w.withdraw_time) = m.m
                    AND EXTRACT(YEAR FROM w.withdraw_time) = :startYear
                    AND w.card_number = :cardNumber
                    AND w.deleted_at IS NULL
                WHERE m.m >= :startMonth AND m.m <= :endMonth
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new WithdrawMonthStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<WithdrawMonthStatusFailed>> findMonthWithdrawStatusFailedByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS VARCHAR) AS reportYear,
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar' WHEN 4 THEN 'Apr'
                        WHEN 5 THEN 'May' WHEN 6 THEN 'Jun' WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug'
                        WHEN 9 THEN 'Sep' WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN w.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalFailed,
                    CAST(COALESCE(SUM(CASE WHEN w.status = 'FAILED' THEN w.withdraw_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN withdraws w
                    ON EXTRACT(MONTH FROM w.withdraw_time) = m.m
                    AND EXTRACT(YEAR FROM w.withdraw_time) = :startYear
                    AND w.card_number = :cardNumber
                    AND w.deleted_at IS NULL
                WHERE m.m >= :startMonth AND m.m <= :endMonth
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .setParameter("startMonth", startMonth)
                .setParameter("endMonth", endMonth)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new WithdrawMonthStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<WithdrawYearStatusSuccess>> findYearlyWithdrawStatusSuccessByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH years AS (
                    SELECT :year AS y UNION ALL SELECT :year - 1 AS y
                )
                SELECT
                    CAST(y.y AS VARCHAR) AS reportYear,
                    CAST(COALESCE(COUNT(w.withdraw_id), 0) AS BIGINT) AS totalSuccess,
                    CAST(COALESCE(SUM(w.withdraw_amount), 0) AS BIGINT) AS totalAmount
                FROM years y
                LEFT JOIN withdraws w
                    ON EXTRACT(YEAR FROM w.withdraw_time) = y.y
                    AND w.status = 'SUCCESS'
                    AND w.card_number = :cardNumber
                    AND w.deleted_at IS NULL
                GROUP BY y.y
                ORDER BY y.y DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new WithdrawYearStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<WithdrawYearStatusFailed>> findYearlyWithdrawStatusFailedByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH years AS (
                    SELECT :year AS y UNION ALL SELECT :year - 1 AS y
                )
                SELECT
                    CAST(y.y AS VARCHAR) AS reportYear,
                    CAST(COALESCE(COUNT(w.withdraw_id), 0) AS BIGINT) AS totalFailed,
                    CAST(COALESCE(SUM(w.withdraw_amount), 0) AS BIGINT) AS totalAmount
                FROM years y
                LEFT JOIN withdraws w
                    ON EXTRACT(YEAR FROM w.withdraw_time) = y.y
                    AND w.status = 'FAILED'
                    AND w.card_number = :cardNumber
                    AND w.deleted_at IS NULL
                GROUP BY y.y
                ORDER BY y.y DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new WithdrawYearStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }
}
