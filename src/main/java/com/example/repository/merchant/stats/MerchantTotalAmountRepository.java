package com.example.repository.merchant.stats;

import java.time.LocalDate;
import java.util.List;

import com.example.entity.merchant.Merchant;
import com.example.entity.merchant.MerchantMonthlyTotalAmount;
import com.example.entity.merchant.MerchantYearlyTotalAmount;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MerchantTotalAmountRepository implements PanacheRepository<Merchant> {

    public Uni<List<MerchantMonthlyTotalAmount>> findMonthlyTotalAmount(LocalDate date) {
        String sql = """
                WITH monthly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transaction_time) AS CHAR(4)) AS reportYear,
                        CASE EXTRACT(MONTH FROM t.transaction_time)
                            WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                            WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                            WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                            WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                        END AS monthName,
                        CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount,
                        CAST(EXTRACT(YEAR FROM t.transaction_time) * 100 + EXTRACT(MONTH FROM t.transaction_time) AS INTEGER) AS month_sort
                    FROM
                        transactions t
                    INNER JOIN
                        merchants m ON t.merchant_id = m.merchant_id
                    WHERE
                        t.deleted_at IS NULL
                        AND m.deleted_at IS NULL
                        AND t.transaction_time >= CAST(DATEADD('MONTH', -1, CAST(:date AS DATE)) AS TIMESTAMP)
                        AND t.transaction_time < CAST(DATEADD('MONTH', 1, CAST(:date AS DATE)) AS TIMESTAMP)
                    GROUP BY
                        EXTRACT(YEAR FROM t.transaction_time),
                        EXTRACT(MONTH FROM t.transaction_time)
                ),
                missing_months AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM CAST(:date AS DATE)) AS CHAR(4)) AS reportYear,
                        CASE EXTRACT(MONTH FROM CAST(:date AS DATE))
                            WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                            WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                            WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                            WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                        END AS monthName,
                        CAST(0 AS BIGINT) AS totalAmount,
                        CAST(EXTRACT(YEAR FROM CAST(:date AS DATE)) * 100 + EXTRACT(MONTH FROM CAST(:date AS DATE)) AS INTEGER) AS month_sort
                    WHERE NOT EXISTS (
                        SELECT 1 FROM monthly_data
                        WHERE month_sort = CAST(EXTRACT(YEAR FROM CAST(:date AS DATE)) * 100 + EXTRACT(MONTH FROM CAST(:date AS DATE)) AS INTEGER)
                    )
                    UNION ALL
                    SELECT
                        CAST(EXTRACT(YEAR FROM DATEADD('MONTH', -1, CAST(:date AS DATE))) AS CHAR(4)) AS reportYear,
                        CASE EXTRACT(MONTH FROM DATEADD('MONTH', -1, CAST(:date AS DATE)))
                            WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                            WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                            WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                            WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                        END AS monthName,
                        CAST(0 AS BIGINT) AS totalAmount,
                        CAST(EXTRACT(YEAR FROM DATEADD('MONTH', -1, CAST(:date AS DATE))) * 100 + EXTRACT(MONTH FROM DATEADD('MONTH', -1, CAST(:date AS DATE))) AS INTEGER) AS month_sort
                    WHERE NOT EXISTS (
                        SELECT 1 FROM monthly_data
                        WHERE month_sort = CAST(EXTRACT(YEAR FROM DATEADD('MONTH', -1, CAST(:date AS DATE))) * 100 + EXTRACT(MONTH FROM DATEADD('MONTH', -1, CAST(:date AS DATE))) AS INTEGER)
                    )
                )
                SELECT reportYear, monthName, totalAmount
                FROM (
                    SELECT reportYear, monthName, totalAmount, month_sort FROM monthly_data
                    UNION ALL
                    SELECT reportYear, monthName, totalAmount, month_sort FROM missing_months
                ) combined
                ORDER BY month_sort DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("date", date)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantMonthlyTotalAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<MerchantYearlyTotalAmount>> findYearlyTotalAmount(Long year, Long previousYear) {
        String sql = """
                WITH yearly_data AS (
                    SELECT
                        CAST(EXTRACT(YEAR FROM t.transaction_time) AS CHAR(4)) AS reportYear,
                        CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                    FROM
                        transactions t
                    INNER JOIN
                        merchants m ON t.merchant_id = m.merchant_id
                    WHERE
                        t.deleted_at IS NULL
                        AND m.deleted_at IS NULL
                        AND EXTRACT(YEAR FROM t.transaction_time) IN (:year, :previousYear)
                    GROUP BY
                        EXTRACT(YEAR FROM t.transaction_time)
                ), formatted_data AS (
                    SELECT reportYear, totalAmount FROM yearly_data
                    UNION ALL
                    SELECT CAST(:year AS CHAR(4)) AS reportYear, CAST(0 AS BIGINT) AS totalAmount
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:year AS CHAR(4)))
                    UNION ALL
                    SELECT CAST(:previousYear AS CHAR(4)) AS reportYear, CAST(0 AS BIGINT) AS totalAmount
                    WHERE NOT EXISTS (SELECT 1 FROM yearly_data WHERE reportYear = CAST(:previousYear AS CHAR(4)))
                )
                SELECT reportYear, totalAmount
                FROM formatted_data
                ORDER BY reportYear DESC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("year", year)
                .setParameter("previousYear", previousYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantYearlyTotalAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
