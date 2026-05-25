package com.example.domain.responses.transaction.stats.status;

import com.example.entity.transaction.TransactionYearStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseYearStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private Long totalFailed;

    public static TransactionResponseYearStatusFailed from(TransactionYearStatusFailed dto) {
        return TransactionResponseYearStatusFailed.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}