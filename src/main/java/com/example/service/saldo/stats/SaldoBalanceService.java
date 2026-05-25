package com.example.service.saldo.stats;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.saldo.stats.balance.SaldoMonthBalanceResponse;
import com.example.domain.responses.saldo.stats.balance.SaldoYearBalanceResponse;

import io.smallrye.mutiny.Uni;

public interface SaldoBalanceService {
    Uni<ApiResponse<List<SaldoMonthBalanceResponse>>> getMonthBalance(Long year);

    Uni<ApiResponse<List<SaldoYearBalanceResponse>>> getYearBalance(Long year);
}
