package com.example.service.merchant.stats.amount;

import java.util.List;

import com.example.domain.requests.merchant.statsbyapikey.MonthYearAmountApiKey;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseMonthlyAmount;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseYearlyAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantAmountByApiKeyService {
    Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> findMonthAmountByApiKey(MonthYearAmountApiKey req);

    Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> findYearAmountByApiKey(MonthYearAmountApiKey req);
}
