package com.example.domain.responses.merchant.stats.method;

import com.example.entity.merchant.MerchantYearlyPaymentMethod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponseYearlyPaymentMethod {
    private String reportYear;
    private String paymentMethod;
    private Long totalAmount;

    public static MerchantResponseYearlyPaymentMethod from(MerchantYearlyPaymentMethod dto) {
        return MerchantResponseYearlyPaymentMethod.builder()
                .reportYear(dto.getYear())
                .paymentMethod(dto.getPaymentMethod())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}
