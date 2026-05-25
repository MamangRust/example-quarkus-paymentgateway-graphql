package com.example.domain.responses.withdraw.stats.status;

import com.example.entity.withdraw.WithdrawYearStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawResponseYearStatusSuccess {
    private String reportYear;
    private Long totalAmount;
    private Long totalSuccess;

    public static WithdrawResponseYearStatusSuccess from(WithdrawYearStatusSuccess dto) {
        return WithdrawResponseYearStatusSuccess.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}
