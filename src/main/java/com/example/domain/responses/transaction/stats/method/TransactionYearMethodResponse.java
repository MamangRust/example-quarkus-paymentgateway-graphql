package com.example.domain.responses.transaction.stats.method;

import com.example.entity.transaction.TransactionYearMethod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionYearMethodResponse {
    private String reportYear;
    private String paymentMethod;
    private Long totalTransactions;
    private Long totalAmount;

    public static TransactionYearMethodResponse from(TransactionYearMethod dto) {
        return TransactionYearMethodResponse.builder()
                .reportYear(dto.getYear())
                .paymentMethod(dto.getPaymentMethod())
                .totalTransactions(dto.getTotalTransactions().longValue())
                .totalAmount(dto.getTotalAmount())
                .build();
    }
}
