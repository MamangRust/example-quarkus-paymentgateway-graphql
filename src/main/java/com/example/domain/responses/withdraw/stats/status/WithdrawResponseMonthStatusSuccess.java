package com.example.domain.responses.withdraw.stats.status;

import com.example.entity.withdraw.WithdrawMonthStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawResponseMonthStatusSuccess {
    private String reportYear;
    private String monthName;
    private Long totalAmount;
    private Long totalSuccess;

    public static WithdrawResponseMonthStatusSuccess from(WithdrawMonthStatusSuccess dto) {
        return WithdrawResponseMonthStatusSuccess.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}