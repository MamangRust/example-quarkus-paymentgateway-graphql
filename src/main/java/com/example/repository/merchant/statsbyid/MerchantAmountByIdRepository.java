package com.example.repository.merchant.statsbyid;

import java.util.List;

import com.example.entity.merchant.Merchant;
import com.example.entity.merchant.MerchantMonthlyAmount;
import com.example.entity.merchant.MerchantYearlyAmount;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MerchantAmountByIdRepository implements PanacheRepository<Merchant> {

    public Uni<List<MerchantMonthlyAmount>> findMonthlyAmountById(Long merchantId, Long year) {
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
                    AND EXTRACT(YEAR FROM t.transaction_time) = :year
                    AND t.deleted_at IS NULL
                LEFT JOIN merchants mch
                    ON t.merchant_id = mch.merchant_id
                    AND mch.deleted_at IS NULL
                    AND mch.merchant_id = :merchantId
                GROUP BY m.m
                ORDER BY m.m
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("merchantId", merchantId)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantMonthlyAmount(
                                (String) row[0],
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }

    public Uni<List<MerchantYearlyAmount>> findYearlyAmountById(Long merchantId, Long endYear) {
        String sql = """
                WITH years AS (
                    SELECT :endYear AS reportYear UNION ALL
                    SELECT :endYear - 1 UNION ALL
                    SELECT :endYear - 2 UNION ALL
                    SELECT :endYear - 3 UNION ALL
                    SELECT :endYear - 4
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM years y
                LEFT JOIN transactions t
                    ON EXTRACT(YEAR FROM t.transaction_time) = y.reportYear
                    AND t.deleted_at IS NULL
                LEFT JOIN merchants m
                    ON t.merchant_id = m.merchant_id
                    AND m.deleted_at IS NULL
                    AND m.merchant_id = :merchantId
                GROUP BY y.reportYear
                ORDER BY y.reportYear
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("merchantId", merchantId)
                .setParameter("endYear", endYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantYearlyAmount(
                                row[0] != null ? row[0].toString().trim() : null,
                                row[1] == null ? 0L : ((Number) row[1]).longValue()))
                        .toList()));
    }
}
