package com.example.repository.merchant.statsbyid;

import java.util.List;

import com.example.entity.merchant.Merchant;
import com.example.entity.merchant.MerchantMonthlyPaymentMethod;
import com.example.entity.merchant.MerchantYearlyPaymentMethod;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MerchantMethodByIdRepository implements PanacheRepository<Merchant> {

    public Uni<List<MerchantMonthlyPaymentMethod>> findMonthlyPaymentMethodsById(Long merchantId, Long year) {
        String sql = """
                WITH months AS (
                    SELECT 1 AS m UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                    SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL
                    SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
                ),
                payment_methods AS (
                    SELECT DISTINCT payment_method
                    FROM transactions t
                    WHERE t.deleted_at IS NULL
                      AND t.merchant_id = :merchantId
                )
                SELECT
                    CASE m.m
                        WHEN 1 THEN 'Jan' WHEN 2 THEN 'Feb' WHEN 3 THEN 'Mar'
                        WHEN 4 THEN 'Apr' WHEN 5 THEN 'May' WHEN 6 THEN 'Jun'
                        WHEN 7 THEN 'Jul' WHEN 8 THEN 'Aug' WHEN 9 THEN 'Sep'
                        WHEN 10 THEN 'Oct' WHEN 11 THEN 'Nov' WHEN 12 THEN 'Dec'
                    END AS monthName,
                    pm.payment_method AS paymentMethod,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM
                    months m
                CROSS JOIN
                    payment_methods pm
                LEFT JOIN
                    transactions t ON EXTRACT(MONTH FROM t.transaction_time) = m.m
                    AND EXTRACT(YEAR FROM t.transaction_time) = :year
                    AND t.payment_method = pm.payment_method
                    AND t.deleted_at IS NULL
                    AND t.merchant_id = :merchantId
                GROUP BY
                    m.m,
                    pm.payment_method
                ORDER BY
                    m.m,
                    pm.payment_method
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("merchantId", merchantId)
                .setParameter("year", year)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantMonthlyPaymentMethod(
                                (String) row[0],
                                (String) row[1],
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }

    public Uni<List<MerchantYearlyPaymentMethod>> findYearlyPaymentMethodsById(Long merchantId, Long endYear) {
        String sql = """
                WITH years AS (
                    SELECT :endYear AS reportYear UNION ALL
                    SELECT :endYear - 1 UNION ALL
                    SELECT :endYear - 2 UNION ALL
                    SELECT :endYear - 3 UNION ALL
                    SELECT :endYear - 4
                ),
                payment_methods AS (
                    SELECT DISTINCT payment_method
                    FROM transactions t
                    WHERE t.deleted_at IS NULL
                      AND t.merchant_id = :merchantId
                )
                SELECT
                    CAST(y.reportYear AS CHAR(4)) AS reportYear,
                    pm.payment_method AS paymentMethod,
                    CAST(COALESCE(SUM(t.amount), 0) AS BIGINT) AS totalAmount
                FROM years y
                CROSS JOIN payment_methods pm
                LEFT JOIN transactions t
                    ON EXTRACT(YEAR FROM t.transaction_time) = y.reportYear
                    AND t.payment_method = pm.payment_method
                    AND t.deleted_at IS NULL
                    AND t.merchant_id = :merchantId
                GROUP BY y.reportYear, pm.payment_method
                ORDER BY y.reportYear ASC, pm.payment_method ASC
                """;

        return getSession().chain(session -> session.createNativeQuery(sql, Object[].class)
                .setParameter("merchantId", merchantId)
                .setParameter("endYear", endYear)
                .getResultList()
                .map(list -> list.stream()
                        .map(row -> new MerchantYearlyPaymentMethod(
                                row[0] != null ? row[0].toString().trim() : null,
                                (String) row[1],
                                row[2] == null ? 0L : ((Number) row[2]).longValue()))
                        .toList()));
    }
}
