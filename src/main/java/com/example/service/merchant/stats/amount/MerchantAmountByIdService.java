package com.example.service.merchant.stats.amount;

import java.util.List;

import com.example.domain.requests.merchant.statsbyid.MonthYearAmountMerchant;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseMonthlyAmount;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseYearlyAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantAmountByIdService {
    Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> findMonthAmountById(MonthYearAmountMerchant req);

    Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> findYearAmountById(MonthYearAmountMerchant req);
}
