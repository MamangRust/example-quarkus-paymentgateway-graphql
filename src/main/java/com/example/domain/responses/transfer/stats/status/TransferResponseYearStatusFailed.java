package com.example.domain.responses.transfer.stats.status;

import com.example.entity.transfer.TransferYearStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponseYearStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private Long totalFailed;

    public static TransferResponseYearStatusFailed from(TransferYearStatusFailed dto) {
        return TransferResponseYearStatusFailed.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}