package com.example.domain.responses.transfer.stats.status;

import com.example.entity.transfer.TransferYearStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponseYearStatusSuccess {
    private String reportYear;
    private Long totalAmount;
    private Long totalSuccess;

    public static TransferResponseYearStatusSuccess from(TransferYearStatusSuccess dto) {
        return TransferResponseYearStatusSuccess.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}