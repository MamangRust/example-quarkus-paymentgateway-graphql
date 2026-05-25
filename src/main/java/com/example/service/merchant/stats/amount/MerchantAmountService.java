package com.example.service.merchant.stats.amount;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseMonthlyAmount;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseYearlyAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantAmountService {
    Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> findMonthAmount(Long year);

    Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> findYearAmount(Long year);
}
