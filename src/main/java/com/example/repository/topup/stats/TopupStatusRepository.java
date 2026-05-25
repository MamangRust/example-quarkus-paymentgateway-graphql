package com.example.repository.topup.stats;

import java.util.List;

import com.example.entity.topup.Topup;
import com.example.entity.topup.TopupMonthStatusFailed;
import com.example.entity.topup.TopupMonthStatusSuccess;
import com.example.entity.topup.TopupYearStatusFailed;
import com.example.entity.topup.TopupYearStatusSuccess;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TopupStatusRepository implements PanacheRepository<Topup> {

    public Uni<List<TopupMonthStatusSuccess>> findMonthTopupStatusSuccess(
            Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
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
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.topup_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN topups t
                    ON EXTRACT(MONTH FROM t.topup_time) = m.mon
                    AND EXTRACT(YEAR FROM t.topup_time) = :startYear
                    AND t.deleted_at IS NULL
                GROUP BY m.mon
                ORDER BY m.mon
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TopupMonthStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TopupMonthStatusFailed>> findMonthTopupStatusFailed(
            Long startYear, Integer startMonth, Long endYear, Integer endMonth) {
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
                    CAST(COALESCE(SUM(CASE WHEN t.status = 'FAILED' THEN t.topup_amount ELSE 0 END), 0) AS BIGINT) AS totalAmount
                FROM months m
                LEFT JOIN topups t
                    ON EXTRACT(MONTH FROM t.topup_time) = m.mon
                    AND EXTRACT(YEAR FROM t.topup_time) = :startYear
                    AND t.deleted_at IS NULL
                GROUP BY m.mon
                ORDER BY m.mon
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("startYear", startYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TopupMonthStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                                row[3] == null ? 0L : ((Number) row[3]).longValue()))
                        .toList()));
    }

    public Uni<List<TopupYearStatusSuccess>> findYearlyTopupStatusSuccess(Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.topup_time) AS VARCHAR) AS reportYear,
                        CAST(COUNT(*) AS BIGINT) AS totalSuccess,
                        CAST(COALESCE(SUM(t.topup_amount), 0) AS BIGINT) AS totalAmount
                    FROM topups t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'SUCCESS'
                      AND (EXTRACT(YEAR FROM t.topup_time) = :year OR EXTRACT(YEAR FROM t.topup_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.topup_time)
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
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TopupYearStatusSuccess(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<TopupYearStatusFailed>> findYearlyTopupStatusFailed(Long year) {
        String sql = """
                WITH yearly_data AS (
                    SELECT CAST(EXTRACT(YEAR FROM t.topup_time) AS VARCHAR) AS reportYear,
                           CAST(COUNT(*) AS BIGINT) AS totalFailed,
                           CAST(COALESCE(SUM(t.topup_amount), 0) AS BIGINT) AS totalAmount
                    FROM topups t
                    WHERE t.deleted_at IS NULL
                      AND t.status = 'FAILED'
                      AND (EXTRACT(YEAR FROM t.topup_time) = :year OR EXTRACT(YEAR FROM t.topup_time) = :year - 1)
                    GROUP BY EXTRACT(YEAR FROM t.topup_time)
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
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new TopupYearStatusFailed(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0 : ((Number) row[1]).intValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }
}
