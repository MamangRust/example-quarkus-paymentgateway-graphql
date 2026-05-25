package com.example.domain.responses.saldo.stats.balance;

import com.example.entity.saldo.SaldoMonthBalance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaldoMonthBalanceResponse {
    private String monthName;
    private Long totalBalance;

    public static SaldoMonthBalanceResponse from(SaldoMonthBalance dto) {
        return SaldoMonthBalanceResponse.builder()
                .monthName(dto.getMonth())
                .totalBalance(dto.getTotalBalance().longValue())
                .build();
    }
}