package com.example.domain.responses.merchant.stats.total_amount;

import com.example.entity.merchant.MerchantMonthlyTotalAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponseMonthlyTotalAmount {
    private String reportYear;
    private String monthName;
    private Long totalAmount;

    public static MerchantResponseMonthlyTotalAmount from(MerchantMonthlyTotalAmount dto) {
        return MerchantResponseMonthlyTotalAmount.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}
