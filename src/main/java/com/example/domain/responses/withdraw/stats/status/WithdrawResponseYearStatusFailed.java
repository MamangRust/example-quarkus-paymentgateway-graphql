package com.example.domain.responses.withdraw.stats.status;

import com.example.entity.withdraw.WithdrawYearStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawResponseYearStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private Long totalFailed;

    public static WithdrawResponseYearStatusFailed from(WithdrawYearStatusFailed dto) {
        return WithdrawResponseYearStatusFailed.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}