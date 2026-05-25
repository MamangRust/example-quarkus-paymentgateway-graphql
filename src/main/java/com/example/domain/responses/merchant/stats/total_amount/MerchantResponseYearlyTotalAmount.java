package com.example.domain.responses.merchant.stats.total_amount;

import com.example.entity.merchant.MerchantYearlyTotalAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponseYearlyTotalAmount {
    private String reportYear;
    private Long totalAmount;

    public static MerchantResponseYearlyTotalAmount from(MerchantYearlyTotalAmount dto) {
        return MerchantResponseYearlyTotalAmount.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}