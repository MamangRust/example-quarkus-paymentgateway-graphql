package com.example.service.saldo.stats;

import java.util.List;

import com.example.domain.requests.saldo.MonthTotalSaldoBalance;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoMonthTotalBalanceResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoYearTotalBalanceResponse;

import io.smallrye.mutiny.Uni;

public interface SaldoTotalAmountService {
    Uni<ApiResponse<List<SaldoMonthTotalBalanceResponse>>> findMonthTotalBalance(MonthTotalSaldoBalance req);

    Uni<ApiResponse<List<SaldoYearTotalBalanceResponse>>> findYearTotalBalance(Long year);
}
