package com.example.repository.transfer.statsbycard;

import java.util.List;

import com.example.entity.transfer.Transfer;
import com.example.entity.transfer.TransferMonthAmount;
import com.example.entity.transfer.TransferYearAmount;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TransferAmountByCardRepository implements PanacheRepository<Transfer> {

    public Uni<List<TransferMonthAmount>> findMonthlyTransferAmountsBySender(String transferFrom, Long year) {
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
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transfers t
                    ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = :year
                    AND t.transfer_from = :transferFrom
                    AND t.deleted_at IS NULL
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("transferFrom", transferFrom)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferYearAmount>> findYearlyTransferAmountsBySender(String transferFrom, Long endYear) {
        String sql = """
                WITH yearly AS (
                    SELECT :endYear AS reportYear UNION ALL
                    SELECT :endYear - 1 UNION ALL
                    SELECT :endYear - 2 UNION ALL
                    SELECT :endYear - 3 UNION ALL
                    SELECT :endYear - 4
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM yearly y
                LEFT JOIN transfers t
                    ON EXTRACT(YEAR FROM t.transfer_time) = y.reportYear
                    AND t.transfer_from = :transferFrom
                    AND t.deleted_at IS NULL
                GROUP BY y.reportYear
                ORDER BY y.reportYear
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("transferFrom", transferFrom)
                .setParameter("endYear", endYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferMonthAmount>> findMonthlyTransferAmountsByReceiver(String transferTo, Long year) {
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
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN transfers t
                    ON EXTRACT(MONTH FROM t.transfer_time) = m.m
                    AND EXTRACT(YEAR FROM t.transfer_time) = :year
                    AND t.transfer_to = :transferTo
                    AND t.deleted_at IS NULL
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("transferTo", transferTo)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferMonthAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<TransferYearAmount>> findYearlyTransferAmountsByReceiver(String transferTo, Long endYear) {
        String sql = """
                WITH yearly AS (
                    SELECT :endYear AS reportYear UNION ALL
                    SELECT :endYear - 1 UNION ALL
                    SELECT :endYear - 2 UNION ALL
                    SELECT :endYear - 3 UNION ALL
                    SELECT :endYear - 4
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(SUM(t.transfer_amount), 0) AS BIGINT) AS totalAmount
                FROM yearly y
                LEFT JOIN transfers t
                    ON EXTRACT(YEAR FROM t.transfer_time) = y.reportYear
                    AND t.transfer_to = :transferTo
                    AND t.deleted_at IS NULL
                GROUP BY y.reportYear
                ORDER BY y.reportYear
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("transferTo", transferTo)
                .setParameter("endYear", endYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TransferYearAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
