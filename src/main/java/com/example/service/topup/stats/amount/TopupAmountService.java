package com.example.service.topup.stats.amount;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.amount.TopupMonthAmountResponse;
import com.example.domain.responses.topup.stats.amount.TopupYearlyAmountResponse;

import io.smallrye.mutiny.Uni;

public interface TopupAmountService {
    Uni<ApiResponse<List<TopupMonthAmountResponse>>> findMonthlyAmounts(Long year);

    Uni<ApiResponse<List<TopupYearlyAmountResponse>>> findYearlyAmounts(Long year);
}
