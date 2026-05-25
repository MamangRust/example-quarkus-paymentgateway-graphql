package com.example.domain.responses.transaction.stats.method;

import com.example.entity.transaction.TransactionMonthMethod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionMonthMethodResponse {
    private String monthName;
    private String paymentMethod;
    private Long totalTransactions;
    private Long totalAmount;

    public static TransactionMonthMethodResponse from(TransactionMonthMethod dto) {
        return TransactionMonthMethodResponse.builder()
                .monthName(dto.getMonth())
                .paymentMethod(dto.getPaymentMethod())
                .totalTransactions(dto.getTotalTransactions().longValue())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}