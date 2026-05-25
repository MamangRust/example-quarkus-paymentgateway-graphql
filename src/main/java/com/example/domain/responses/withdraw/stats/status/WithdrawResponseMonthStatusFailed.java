package com.example.domain.responses.withdraw.stats.status;

import com.example.entity.withdraw.WithdrawMonthStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawResponseMonthStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private String monthName;
    private Long totalFailed;

    public static WithdrawResponseMonthStatusFailed from(WithdrawMonthStatusFailed dto) {
        return WithdrawResponseMonthStatusFailed.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}