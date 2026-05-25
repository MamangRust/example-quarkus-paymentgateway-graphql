package com.example.domain.responses.transaction.stats.amount;

import com.example.entity.transaction.TransactionMonthAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionMonthAmountResponse {
    private String monthName;
    private Long totalAmount;

    public static TransactionMonthAmountResponse from(TransactionMonthAmount dto) {
        return TransactionMonthAmountResponse.builder()
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}