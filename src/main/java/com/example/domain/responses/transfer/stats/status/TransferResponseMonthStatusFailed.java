package com.example.domain.responses.transfer.stats.status;

import com.example.entity.transfer.TransferMonthStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponseMonthStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private String monthName;
    private Long totalFailed;

    public static TransferResponseMonthStatusFailed from(TransferMonthStatusFailed dto) {
        return TransferResponseMonthStatusFailed.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}