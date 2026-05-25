package com.example.repository.saldo.stats;

import java.util.List;

import com.example.entity.saldo.Saldo;
import com.example.entity.saldo.SaldoMonthTotalBalance;
import com.example.entity.saldo.SaldoYearTotalBalance;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SaldoTotalAmountRepository implements PanacheRepository<Saldo> {

    public Uni<List<SaldoMonthTotalBalance>> findMonthlyTotalSaldoBalance(
            Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
        String sql = """
                WITH complete_months AS (
                    SELECT CAST(:startYear AS INTEGER) AS reportYear, 1 AS mon UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 2 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 3 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 4 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 5 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 6 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 7 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 8 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 9 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 10 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 11 UNION ALL
                    SELECT CAST(:startYear AS INTEGER), 12
                ),
                monthly_totals AS (
                    SELECT
                        EXTRACT(YEAR FROM s.created_at) AS reportYear,
                        EXTRACT(MONTH FROM s.created_at) AS mon,
                        SUM(s.total_balance) AS total_balance
                    FROM saldos s
                    WHERE s.deleted_at IS NULL
                      AND EXTRACT(YEAR FROM s.created_at) = CAST(:startYear AS INTEGER)
                    GROUP BY EXTRACT(YEAR FROM s.created_at), EXTRACT(MONTH FROM s.created_at)
                )
                SELECT
                    CAST(cm.reportYear AS CHAR(4)) AS reportYear,
                    CASE cm.mon
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                        WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                        WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                        WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    CAST(COALESCE(mt.total_balance, 0) AS BIGINT) AS total_balance
                FROM complete_months cm
                LEFT JOIN monthly_totals mt
                    ON cm.reportYear = mt.reportYear
                    AND cm.mon = mt.mon
                ORDER BY cm.reportYear DESC, cm.mon DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new SaldoMonthTotalBalance(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<SaldoYearTotalBalance>> findYearlyTotalSaldoBalance(Long year) {
        String sql = """
                WITH years AS (
                    SELECT :year AS y UNION ALL
                    SELECT :year - 1
                )
                SELECT
                    CAST(y.y AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(SUM(s.total_balance), 0) AS BIGINT) AS totalBalance
                FROM years y
                LEFT JOIN saldos s
                    ON EXTRACT(YEAR FROM s.created_at) = y.y
                    AND s.deleted_at IS NULL
                GROUP BY y.y
                ORDER BY y.y DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new SaldoYearTotalBalance(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
