package com.example.domain.responses.transaction.stats.status;

import com.example.entity.transaction.TransactionMonthStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseMonthStatusSuccess {
    private String reportYear;
    private String monthName;
    private Long totalAmount;
    private Long totalSuccess;

    public static TransactionResponseMonthStatusSuccess from(TransactionMonthStatusSuccess dto) {
        return TransactionResponseMonthStatusSuccess.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}