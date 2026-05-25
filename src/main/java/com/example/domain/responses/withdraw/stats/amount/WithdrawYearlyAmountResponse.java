package com.example.domain.responses.withdraw.stats.amount;

import com.example.entity.withdraw.WithdrawYearlyAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawYearlyAmountResponse {
    private String reportYear;
    private Long totalAmount;

    public static WithdrawYearlyAmountResponse from(WithdrawYearlyAmount dto) {
        return WithdrawYearlyAmountResponse.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .build();
    }
}