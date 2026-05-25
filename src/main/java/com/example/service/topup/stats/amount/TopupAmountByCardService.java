package com.example.service.topup.stats.amount;

import java.util.List;

import com.example.domain.requests.topup.stats.YearMonthMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.amount.TopupMonthAmountResponse;
import com.example.domain.responses.topup.stats.amount.TopupYearlyAmountResponse;

import io.smallrye.mutiny.Uni;

public interface TopupAmountByCardService {
    Uni<ApiResponse<List<TopupMonthAmountResponse>>> findMonthlyAmounts(YearMonthMethod req);

    Uni<ApiResponse<List<TopupYearlyAmountResponse>>> findYearlyAmounts(YearMonthMethod req);
}
