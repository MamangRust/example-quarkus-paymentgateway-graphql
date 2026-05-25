package com.example.domain.responses.transaction.stats.status;

import com.example.entity.transaction.TransactionYearStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseYearStatusSuccess {
    private String reportYear;
    private Long totalAmount;
    private Long totalSuccess;

    public static TransactionResponseYearStatusSuccess from(TransactionYearStatusSuccess dto) {
        return TransactionResponseYearStatusSuccess.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}