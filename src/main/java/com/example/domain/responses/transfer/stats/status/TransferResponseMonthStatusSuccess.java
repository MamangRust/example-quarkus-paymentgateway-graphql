package com.example.domain.responses.transfer.stats.status;

import com.example.entity.transfer.TransferMonthStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponseMonthStatusSuccess {
    private String reportYear;
    private String monthName;
    private Long totalAmount;
    private Long totalSuccess;

    public static TransferResponseMonthStatusSuccess from(TransferMonthStatusSuccess dto) {
        return TransferResponseMonthStatusSuccess.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}