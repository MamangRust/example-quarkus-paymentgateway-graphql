package com.example.service.withdraw.stats.amount;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawMonthlyAmountResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawYearlyAmountResponse;

import io.smallrye.mutiny.Uni;

public interface WithdrawAmountService {
    Uni<ApiResponse<List<WithdrawMonthlyAmountResponse>>> findMonthlyWithdraws(Long year);

    Uni<ApiResponse<List<WithdrawYearlyAmountResponse>>> findYearlyWithdraws(Long year);
}
