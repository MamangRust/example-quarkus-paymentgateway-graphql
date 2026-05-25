package com.example.domain.responses.transaction.stats.status;

import com.example.entity.transaction.TransactionMonthStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseMonthStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private String monthName;
    private Long totalFailed;

    public static TransactionResponseMonthStatusFailed from(TransactionMonthStatusFailed dto) {
        return TransactionResponseMonthStatusFailed.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}