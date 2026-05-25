package com.example.repository.transfer.statsbycard;

import java.util.List;

import com.example.entity.transfer.Transfer;
import com.example.entity.transfer.TransferMonthStatusFailed;
import com.example.entity.transfer.TransferMonthStatusSuccess;
import com.example.entity.transfer.TransferYearStatusFailed;
import com.example.entity.transfer.TransferYearStatusSuccess;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransferStatusByCardRepository implements PanacheRepository<Transfer> {

    public Uni<List<TransferMonthStatusSuccess>> findMonthTransferStatusSuccessByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS CHAR(4)) AS reportYear,
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                        WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                        WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                        WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalSuccess,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.transfer_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transfers t
                    ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = :startYear
                    AND (t.transfer_from = :cardNumber OR t.transfer_to = :cardNumber)
                    AND t.deleted_at IS NULL
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferMonthStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferMonthStatusFailed>> findMonthTransferStatusFailedByCard(
            String cardNumber, Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                )
                SELECT
                    CAST(:startYear AS CHAR(4)) AS reportYear,
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                        WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                        WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                        WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS BIGINT) AS totalFailed,
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'FAILED' THEN t.transfer_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transfers t
                    ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = :startYear
                    AND (t.transfer_from = :cardNumber OR t.transfer_to = :cardNumber)
                    AND t.deleted_at IS NULL
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferMonthStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferYearStatusSuccess>> findYearlyTransferStatusSuccessByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transfer_time) AS CHAR(4)) AS reportYear,
                        COUNT(*) AS total_success,
                        COALESCE(SUM(t.transfer_amount), 0) AS totalAmount
                    FROM transfers t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'SUCCESS'
                      AND (t.transfer_from = :cardNumber OR t.transfer_to = :cardNumber)
                      AND (EXTRACT(YEAR FROM t.transfer_time) = :year OR EXTRACT(YEAR FROM t.transfer_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.transfer_time)
                ), formatted_data AS (
                    SELECT reportYear, CAST(total_success AS BIGINT) AS totalSuccess, CAST(totalAmount AS BIGINT) AS totalAmount FROM yearly_data
                    UNION ALL
                    SELECT CAST(:year AS CHAR(4)), 0, 0 WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year AS CHAR(4)))
                    UNION ALL
                    SELECT CAST(:year - 1 AS CHAR(4)), 0, 0 WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year - 1 AS CHAR(4)))
                )
                SELECT * FROM formatted_data
                ORDER BY reportYear DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferYearStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferYearStatusFailed>> findYearlyTransferStatusFailedByCard(
            String cardNumber, Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transfer_time) AS CHAR(4)) AS reportYear,
                        COUNT(*) AS total_failed,
                        COALESCE(SUM(t.transfer_amount), 0) AS totalAmount
                    FROM transfers t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'FAILED'
                      AND (t.transfer_from = :cardNumber OR t.transfer_to = :cardNumber)
                      AND (EXTRACT(YEAR FROM t.transfer_time) = :year OR EXTRACT(YEAR FROM t.transfer_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.transfer_time)
                ), formatted_data AS (
                    SELECT reportYear, CAST(total_failed AS BIGINT) AS totalFailed, CAST(totalAmount AS BIGINT) AS totalAmount FROM yearly_data
                    UNION ALL
                    SELECT CAST(:year AS CHAR(4)), 0, 0 WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year AS CHAR(4)))
                    UNION ALL
                    SELECT CAST(:year - 1 AS CHAR(4)), 0, 0 WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year - 1 AS CHAR(4)))
                )
                SELECT * FROM formatted_data
                ORDER BY reportYear DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("cardNumber", cardNumber)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferYearStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }
}
