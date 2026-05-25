package com.example.domain.responses.saldo.stats.total_balance;

import com.example.entity.saldo.SaldoMonthTotalBalance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaldoMonthTotalBalanceResponse {
    private String monthName;
    private String reportYear;
    private Long totalBalance;

    public static SaldoMonthTotalBalanceResponse from(SaldoMonthTotalBalance dto) {
        return SaldoMonthTotalBalanceResponse.builder()
                .monthName(dto.getMonth())
                .reportYear(dto.getYear())
                .totalBalance(dto.getTotalBalance().longValue())
                .build();
    }
}