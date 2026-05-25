package com.example.domain.responses.saldo.stats.total_balance;

import com.example.entity.saldo.SaldoYearTotalBalance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaldoYearTotalBalanceResponse {
    private String reportYear;
    private Long totalBalance;

    public static SaldoYearTotalBalanceResponse from(SaldoYearTotalBalance dto) {
        return SaldoYearTotalBalanceResponse.builder()
                .reportYear(dto.getYear())
                .totalBalance(dto.getTotalBalance().longValue())
                .build();
    }
}
