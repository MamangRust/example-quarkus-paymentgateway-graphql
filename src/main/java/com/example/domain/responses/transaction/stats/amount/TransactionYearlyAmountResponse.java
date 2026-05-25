package com.example.domain.responses.transaction.stats.amount;

import com.example.entity.transaction.TransactionYearAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionYearlyAmountResponse {
    private String year;
    private Long totalAmount;

    public static TransactionYearlyAmountResponse from(TransactionYearAmount dto) {
        return TransactionYearlyAmountResponse.builder()
                .year(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .build();
    }
}